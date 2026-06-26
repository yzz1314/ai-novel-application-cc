"""
全文分析Agent
逐块分析文本，提取写作技巧
"""
import json
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from llm.client import LLMClient
from llm.prompt_builder import PromptBuilder
from text_processing import CoverageValidator
from config import settings
from utils.logger import get_logger
from utils.checkpoint_manager import CheckpointManager


class FullTextAnalysisAgent(BaseAgent):
    """全文分析Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("FullTextAnalysisAgent")
        self.supported_tasks = ["full_text_analysis"]
        self.llm_client = llm_client or LLMClient()
        self.prompt_builder = PromptBuilder()
        self.logger = get_logger("FullTextAnalysisAgent")
        self.coverage_validator = CoverageValidator()
        self.checkpoints = CheckpointManager()

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行全文分析任务

        流程：
        1. 读取manifest和chunks
        2. 加载Skill（如果有）
        3. 逐块调用LLM分析
        4. 保存每个chunk的分析结果
        5. 生成分析摘要
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)

            project_id = request.project_id
            sample_id = request.input_refs.get("sample_id") or request.parameters.get("sample_id")
            skill_name = request.parameters.get("skill_name", "")

            self.logger.info(f"Analyzing sample: {sample_id}")

            # 2. 读取manifest
            manifest = await self._read_manifest(project_id, sample_id)

            # 3. 读取所有chunks
            chunks = await self._read_chunks(project_id, sample_id)
            self.logger.info(f"Loaded {len(chunks)} chunks")
            requested_chunk_ids = {
                str(chunk_id)
                for chunk_id in request.parameters.get("chunk_ids", [])
                if chunk_id is not None
            }

            # 4. 加载Skill内容（如果指定）
            skill_content = ""
            if skill_name:
                skill_content = await self._load_skill(skill_name)
                self.logger.info(f"Loaded skill: {skill_name}")

            # 5. 逐块分析。支持从 checkpoint 恢复，并优先复用已落盘的 chunk 分析结果。
            resume_state = self._load_resume_state(request)
            analysis_results = self._load_existing_analysis_results(project_id, sample_id)
            errors_list = list(resume_state.get("errors", []))
            warnings_list = []
            completed_chunk_ids = {
                str(result.get("chunk_id"))
                for result in analysis_results
                if result.get("chunk_id") is not None
            }
            completed_chunk_ids.update(str(chunk_id) for chunk_id in resume_state.get("completed_chunk_ids", []))
            failed_chunk_ids = {
                str(error.get("chunk_id"))
                for error in errors_list
                if error.get("chunk_id") is not None
            }
            checkpoint_ref = resume_state.get("checkpoint_ref")
            skipped_completed_chunks = 0
            skipped_out_of_scope_chunks = 0
            if resume_state:
                warnings_list.append({
                    "code": "ANALYSIS_RESUMED_FROM_CHECKPOINT",
                    "message": f"Resumed from checkpoint: {request.resume_from_checkpoint}",
                    "retryable": False
                })

            for i, chunk in enumerate(chunks, 1):
                chunk_id = str(chunk["id"])
                if requested_chunk_ids and chunk_id not in requested_chunk_ids:
                    skipped_out_of_scope_chunks += 1
                    continue
                if chunk_id in completed_chunk_ids and chunk_id not in failed_chunk_ids:
                    self.logger.info(f"Skipping completed chunk {i}/{len(chunks)}: {chunk_id}")
                    skipped_completed_chunks += 1
                    continue

                self.logger.info(f"Analyzing chunk {i}/{len(chunks)}: {chunk['id']}")

                try:
                    # 构建prompt
                    prompt = self.prompt_builder.build_chunk_analysis_prompt(
                        chunk_text=chunk.get("content") or chunk.get("text", ""),
                        chapter_range=chunk.get("chapter_range", "未知"),
                        skill_content=skill_content
                    )

                    # 调用LLM
                    llm_response = await self.llm_client.generate_with_retry(
                        prompt=prompt,
                        response_format="json",
                        max_retries=3
                    )

                    # 更新指标
                    self.metrics["llm_calls"] += 1
                    self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
                    self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

                    # 解析JSON响应
                    analysis_data = json.loads(llm_response["content"])

                    # 添加元数据
                    analysis_result = {
                        "chunk_id": chunk["id"],
                        "chunk_index": i - 1,
                        "start_pos": chunk.get("start") or chunk.get("start_offset", 0),
                        "end_pos": chunk.get("end") or chunk.get("end_offset", 0),
                        "chapter_range": chunk.get("chapter_range", "未知"),
                        "analysis": analysis_data,
                        "analyzed_at": datetime.now().isoformat()
                    }

                    analysis_results.append(analysis_result)
                    completed_chunk_ids.add(chunk_id)
                    failed_chunk_ids.discard(chunk_id)
                    errors_list = [
                        error for error in errors_list
                        if str(error.get("chunk_id")) != chunk_id
                    ]

                    # 保存单个chunk的分析结果
                    await self._save_chunk_analysis(
                        project_id, sample_id, chunk["id"], analysis_result
                    )
                    checkpoint_ref = self._save_analysis_checkpoint(
                        request,
                        sample_id,
                        len(chunks),
                        completed_chunk_ids,
                        errors_list,
                        next_chunk_index=i
                    )

                    self.logger.info(f"Chunk {i} analysis completed")

                except json.JSONDecodeError as e:
                    error_msg = f"Failed to parse JSON for chunk {chunk['id']}: {str(e)}"
                    self.logger.error(error_msg)
                    errors_list.append({
                        "code": "JSON_PARSE_ERROR",
                        "message": error_msg,
                        "chunk_id": chunk["id"],
                        "retryable": True
                    })
                    failed_chunk_ids.add(chunk_id)
                    checkpoint_ref = self._save_analysis_checkpoint(
                        request,
                        sample_id,
                        len(chunks),
                        completed_chunk_ids,
                        errors_list,
                        next_chunk_index=i
                    )

                except Exception as e:
                    error_msg = f"Failed to analyze chunk {chunk['id']}: {str(e)}"
                    self.logger.error(error_msg)
                    errors_list.append({
                        "code": "ANALYSIS_ERROR",
                        "message": error_msg,
                        "chunk_id": chunk["id"],
                        "retryable": True
                    })
                    failed_chunk_ids.add(chunk_id)
                    checkpoint_ref = self._save_analysis_checkpoint(
                        request,
                        sample_id,
                        len(chunks),
                        completed_chunk_ids,
                        errors_list,
                        next_chunk_index=i
                    )

            checkpoint_ref = self._save_analysis_checkpoint(
                request,
                sample_id,
                len(chunks),
                completed_chunk_ids,
                errors_list,
                next_chunk_index=len(chunks)
            )

            # 6. 生成分析摘要
            analysis_summary = self._generate_summary(analysis_results, manifest)

            # 7. 保存汇总结果
            await self._save_analysis_summary(project_id, sample_id, analysis_summary)
            coverage_report = await self._save_coverage_report(
                project_id,
                sample_id,
                manifest,
                chunks,
                analysis_results,
                errors_list
            )

            # 8. 构建响应
            output_refs = [
                f"analysis/per_chunk/{sample_id}/",
                f"analysis/per_chunk/{sample_id}/summary.json",
                f"analysis/coverage/{sample_id}_coverage.json"
            ]

            structured_output = {
                "sample_id": sample_id,
                "total_chunks": len(chunks),
                "analyzed_chunks": len(analysis_results),
                "failed_chunks": len(errors_list),
                "analysis_summary": analysis_summary,
                "coverage_report": coverage_report,
                "checkpoint_ref": checkpoint_ref,
                "resumed_from_checkpoint": request.resume_from_checkpoint,
                "skipped_completed_chunks": skipped_completed_chunks,
                "skipped_out_of_scope_chunks": skipped_out_of_scope_chunks
            }

            # 如果有失败的chunk，设置warning
            if errors_list:
                warnings_list.append({
                    "code": "PARTIAL_ANALYSIS",
                    "message": f"{len(errors_list)} chunks failed to analyze"
                })

            if len(analysis_results) == len(chunks):
                status = "success"
            elif analysis_results:
                status = "partial"
            else:
                status = "failed"

            response = self._build_response(
                request=request,
                status=status,
                output_refs=output_refs,
                structured_output=structured_output,
                errors=errors_list,
                warnings=warnings_list
            )
            response.checkpoint_ref = checkpoint_ref
            return response

        except Exception as e:
            self.logger.error(f"Full text analysis failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "ANALYSIS_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    def _load_resume_state(self, request: AgentRequest) -> Dict[str, Any]:
        """Load checkpoint state if resume_from_checkpoint is provided."""
        if not request.resume_from_checkpoint:
            return {}
        state = self.checkpoints.load(request.project_id, request.resume_from_checkpoint)
        if state.get("task_type") != "full_text_analysis":
            raise ValueError("checkpoint is not for full_text_analysis")
        return dict(state, checkpoint_ref=request.resume_from_checkpoint)

    def _load_existing_analysis_results(self, project_id: str, sample_id: str) -> List[Dict[str, Any]]:
        """Read completed chunk analyses from disk for idempotent resume/retry."""
        analysis_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_chunk" / sample_id
        )
        if not analysis_dir.exists():
            return []

        results: List[Dict[str, Any]] = []
        for analysis_file in sorted(analysis_dir.glob("*_analysis.json")):
            try:
                with open(analysis_file, "r", encoding="utf-8") as f:
                    result = json.load(f)
                if result.get("chunk_id") is not None:
                    results.append(result)
            except Exception as exc:
                self.logger.warning(f"Failed to load existing analysis {analysis_file}: {exc}")
        return results

    def _save_analysis_checkpoint(
            self,
            request: AgentRequest,
            sample_id: str,
            total_chunks: int,
            completed_chunk_ids: set,
            errors_list: List[Dict[str, Any]],
            next_chunk_index: int) -> str:
        """Persist per-chunk analysis progress after each processed chunk."""
        state = {
            "task_type": "full_text_analysis",
            "task_id": request.task_id,
            "project_id": request.project_id,
            "sample_id": sample_id,
            "total_chunks": total_chunks,
            "processed_chunks": len(completed_chunk_ids),
            "completed_chunk_ids": sorted(completed_chunk_ids),
            "failed_chunk_ids": sorted({
                str(error.get("chunk_id"))
                for error in errors_list
                if error.get("chunk_id") is not None
            }),
            "errors": errors_list,
            "next_chunk_index": next_chunk_index,
            "updated_at": datetime.now().isoformat(),
        }
        checkpoint_ref = self.checkpoints.save(
            request.project_id,
            request.task_id,
            f"full_text_analysis_{sample_id}",
            state
        )
        self.logger.info(f"Saved full_text_analysis checkpoint: {checkpoint_ref}")
        return checkpoint_ref

    async def _read_manifest(self, project_id: str, sample_id: str) -> Dict:
        """读取manifest"""
        manifest_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "manifests" / f"{sample_id}_manifest.json"
        )

        if not manifest_file.exists():
            raise FileNotFoundError(f"Manifest not found: {manifest_file}")

        with open(manifest_file, 'r', encoding='utf-8') as f:
            return json.load(f)

    async def _read_chunks(self, project_id: str, sample_id: str) -> List[Dict]:
        """读取所有chunks"""
        chunks_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "chunks" / sample_id
        )

        if not chunks_dir.exists():
            raise FileNotFoundError(f"Chunks directory not found: {chunks_dir}")

        chunks = []
        for chunk_file in sorted(chunks_dir.glob("*.json")):
            with open(chunk_file, 'r', encoding='utf-8') as f:
                chunks.append(json.load(f))

        return chunks

    async def _load_skill(self, skill_name: str) -> str:
        """加载Skill内容"""
        skill_file = Path(settings.SKILLS_PATH) / f"{skill_name}.md"

        if not skill_file.exists():
            self.logger.warning(f"Skill file not found: {skill_file}")
            return ""

        with open(skill_file, 'r', encoding='utf-8') as f:
            return f.read()

    async def _save_chunk_analysis(self, project_id: str, sample_id: str,
                                   chunk_id: str, analysis_result: Dict):
        """保存单个chunk的分析结果"""
        analysis_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_chunk" / sample_id
        )
        analysis_dir.mkdir(parents=True, exist_ok=True)

        analysis_file = analysis_dir / f"{chunk_id}_analysis.json"
        with open(analysis_file, 'w', encoding='utf-8') as f:
            json.dump(analysis_result, f, ensure_ascii=False, indent=2)

        legacy_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "analysis" / sample_id
        )
        legacy_dir.mkdir(parents=True, exist_ok=True)
        with open(legacy_dir / f"{chunk_id}_analysis.json", 'w', encoding='utf-8') as f:
            json.dump(analysis_result, f, ensure_ascii=False, indent=2)

    def _generate_summary(self, analysis_results: List[Dict],
                         manifest: Dict) -> Dict:
        """生成分析摘要"""
        # 统计各类技巧出现次数
        technique_stats = {
            "scene_techniques": {},
            "prose_techniques": {},
            "outline_techniques": {},
            "conflict_types": {"external": 0, "internal": 0, "social": 0},
            "appeal_types": {}
        }

        for result in analysis_results:
            analysis = result.get("analysis", {})

            # 统计场景技巧
            for tech in analysis.get("scene_techniques", []):
                scene_type = tech.get("scene_type", "未知")
                technique_stats["scene_techniques"][scene_type] = \
                    technique_stats["scene_techniques"].get(scene_type, 0) + 1

            # 统计文笔技巧
            for tech in analysis.get("prose_techniques", []):
                tech_type = tech.get("technique_type", "未知")
                technique_stats["prose_techniques"][tech_type] = \
                    technique_stats["prose_techniques"].get(tech_type, 0) + 1

            # 统计大纲技巧
            for tech in analysis.get("outline_techniques", []):
                tech_type = tech.get("technique_type", "未知")
                technique_stats["outline_techniques"][tech_type] = \
                    technique_stats["outline_techniques"].get(tech_type, 0) + 1

            # 统计冲突类型
            conflict = analysis.get("conflict", {})
            if conflict.get("external"):
                technique_stats["conflict_types"]["external"] += 1
            if conflict.get("internal"):
                technique_stats["conflict_types"]["internal"] += 1
            if conflict.get("social"):
                technique_stats["conflict_types"]["social"] += 1

            # 统计爽点类型
            for appeal in analysis.get("appeal_points", []):
                appeal_type = appeal.get("type", "未知")
                technique_stats["appeal_types"][appeal_type] = \
                    technique_stats["appeal_types"].get(appeal_type, 0) + 1

        return {
            "sample_id": manifest.get("sample_id"),
            "title": manifest.get("title"),
            "total_chars": manifest.get("total_chars"),
            "total_chapters": manifest.get("total_chapters"),
            "analyzed_chunks": len(analysis_results),
            "technique_stats": technique_stats,
            "created_at": datetime.now().isoformat()
        }

    async def _save_analysis_summary(self, project_id: str, sample_id: str,
                                    summary: Dict):
        """保存分析摘要"""
        analysis_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_chunk" / sample_id
        )
        analysis_dir.mkdir(parents=True, exist_ok=True)

        summary_file = analysis_dir / "summary.json"
        with open(summary_file, 'w', encoding='utf-8') as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)

        legacy_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "analysis" / sample_id
        )
        legacy_dir.mkdir(parents=True, exist_ok=True)
        with open(legacy_dir / "summary.json", 'w', encoding='utf-8') as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)

        self.logger.info(f"Saved analysis summary to {summary_file}")

    async def _save_coverage_report(
            self,
            project_id: str,
            sample_id: str,
            manifest: Dict,
            chunks: List[Dict],
            analysis_results: List[Dict],
            errors_list: List[Dict]) -> Dict:
        """保存文本分块和逐块分析覆盖率报告"""
        text_coverage = self.coverage_validator.validate(
            int(manifest.get("total_chars") or 0),
            chunks
        )
        analyzed_ids = {
            str(result.get("chunk_id"))
            for result in analysis_results
            if result.get("chunk_id") is not None
        }
        chunk_ids = [
            str(chunk.get("id"))
            for chunk in chunks
            if chunk.get("id") is not None
        ]
        missing_ids = [chunk_id for chunk_id in chunk_ids if chunk_id not in analyzed_ids]
        failed_chunks = [
            {
                "chunk_id": error.get("chunk_id"),
                "code": error.get("code"),
                "message": error.get("message"),
                "retryable": error.get("retryable", True)
            }
            for error in errors_list
        ]
        total_chunks = len(chunk_ids)
        analyzed_count = len([chunk_id for chunk_id in chunk_ids if chunk_id in analyzed_ids])
        analysis_coverage = {
            "total_chunks": total_chunks,
            "analyzed_chunks": analyzed_count,
            "coverage_ratio": analyzed_count / total_chunks if total_chunks else 0,
            "is_complete": total_chunks > 0 and analyzed_count == total_chunks and not failed_chunks,
            "missing_analysis_count": len(missing_ids),
            "missing_analysis_chunks": missing_ids,
            "failed_chunk_count": len(failed_chunks),
            "failed_chunks": failed_chunks,
            "extra_analysis_chunks": sorted(analyzed_ids.difference(chunk_ids)),
        }
        report = {
            "project_id": project_id,
            "sample_id": sample_id,
            "title": manifest.get("title"),
            "created_at": datetime.now().isoformat(),
            "status": (
                "passed"
                if text_coverage.get("is_complete") and analysis_coverage["is_complete"]
                else "needs_attention"
            ),
            "thresholds": {
                "text_coverage_ratio": 0.999,
                "analysis_coverage_ratio": 1.0,
            },
            "text_coverage": text_coverage,
            "analysis_coverage": analysis_coverage,
            "manifest_path": f"samples/manifests/{sample_id}_manifest.json",
            "chunks_path": f"samples/chunks/{sample_id}/",
            "analysis_path": f"analysis/per_chunk/{sample_id}/",
        }

        coverage_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "coverage"
        )
        coverage_dir.mkdir(parents=True, exist_ok=True)
        coverage_file = coverage_dir / f"{sample_id}_coverage.json"
        with open(coverage_file, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)

        self.logger.info(f"Saved coverage report to {coverage_file}")
        return {
            "status": report["status"],
            "text_coverage_ratio": text_coverage["coverage_ratio"],
            "analysis_coverage_ratio": analysis_coverage["coverage_ratio"],
            "missing_analysis_count": analysis_coverage["missing_analysis_count"],
            "failed_chunk_count": analysis_coverage["failed_chunk_count"],
            "report_path": f"analysis/coverage/{sample_id}_coverage.json",
        }
