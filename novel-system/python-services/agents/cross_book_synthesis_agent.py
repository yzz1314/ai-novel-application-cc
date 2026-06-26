"""
跨书归纳Agent
基于多本样本的分析报告归纳出可复用的写作规律
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
from config import settings
from utils.logger import get_logger


class CrossBookSynthesisAgent(BaseAgent):
    """跨书归纳Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("CrossBookSynthesisAgent")
        self.supported_tasks = ["cross_book_synthesis"]
        self.llm_client = llm_client or LLMClient()
        self.prompt_builder = PromptBuilder()
        self.logger = get_logger("CrossBookSynthesisAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行跨书归纳任务

        流程：
        1. 读取所有样本的分析报告
        2. 构建跨书归纳prompt
        3. 调用LLM生成归纳报告
        4. 保存归纳报告
        5. 生成项目专属Skill建议
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)

            project_id = request.project_id
            sample_ids = request.input_refs.get("sample_ids") or request.parameters.get("sample_ids", [])

            if not sample_ids or len(sample_ids) < 2:
                raise ValueError("至少需要2本样本书籍进行跨书归纳")

            self.logger.info(f"Cross-book synthesis for {len(sample_ids)} samples")

            # 2. 读取所有书籍报告
            book_reports = []
            for sample_id in sample_ids:
                try:
                    report = await self._read_book_report(project_id, sample_id)
                    summary = await self._read_analysis_summary(project_id, sample_id)

                    book_reports.append({
                        "sample_id": sample_id,
                        "title": summary.get("title", "未知"),
                        "report_content": report,
                        "technique_stats": summary.get("technique_stats", {})
                    })
                except Exception as e:
                    self.logger.warning(f"Failed to load report for {sample_id}: {str(e)}")

            if len(book_reports) < 2:
                raise ValueError("成功加载的报告少于2本，无法进行跨书归纳")

            self.logger.info(f"Loaded {len(book_reports)} book reports")

            # 3. 构建prompt（只传递关键信息，避免超出token限制）
            # 简化版：只传递标题和技巧统计
            simplified_reports = [
                {
                    "title": report["title"],
                    "technique_stats": report["technique_stats"]
                }
                for report in book_reports
            ]

            prompt = self.prompt_builder.build_cross_book_synthesis_prompt(
                book_reports=simplified_reports
            )

            # 4. 调用LLM生成归纳报告
            self.logger.info("Calling LLM to generate cross-book synthesis...")
            llm_response = await self.llm_client.generate_with_retry(
                prompt=prompt,
                response_format="text",
                max_retries=3,
                max_tokens=10000  # 归纳报告可能很长
            )

            # 更新指标
            self.metrics["llm_calls"] += 1
            self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
            self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

            synthesis_content = llm_response["content"]

            # 5. 保存归纳报告
            synthesis_path = await self._save_synthesis(
                project_id, synthesis_content
            )

            # 6. 生成技巧汇总
            technique_summary = self._aggregate_techniques(book_reports)

            # 7. 保存技巧汇总
            await self._save_technique_summary(project_id, technique_summary)

            # 8. 构建响应
            output_refs = [
                "analysis/cross_book/cross_book_synthesis.md",
                "analysis/cross_book/technique_summary.json"
            ]

            structured_output = {
                "project_id": project_id,
                "sample_count": len(book_reports),
                "sample_ids": sample_ids,
                "synthesis_length": len(synthesis_content),
                "technique_summary": technique_summary,
                "created_at": datetime.now().isoformat()
            }

            return self._build_response(
                request=request,
                status="success",
                output_refs=output_refs,
                structured_output=structured_output
            )

        except Exception as e:
            self.logger.error(f"Cross-book synthesis failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "SYNTHESIS_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    async def _read_book_report(self, project_id: str, sample_id: str) -> str:
        """读取单书报告"""
        report_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_book" / f"{sample_id}_report.md"
        )
        legacy_report_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "reports" / f"{sample_id}_report.md"
        )

        if not report_file.exists() and legacy_report_file.exists():
            report_file = legacy_report_file

        if not report_file.exists():
            raise FileNotFoundError(f"Book report not found: {report_file}")

        with open(report_file, 'r', encoding='utf-8') as f:
            return f.read()

    async def _read_analysis_summary(self, project_id: str, sample_id: str) -> Dict:
        """读取分析摘要"""
        summary_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_chunk" / sample_id / "summary.json"
        )
        legacy_summary_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "analysis" / sample_id / "summary.json"
        )

        if not summary_file.exists() and legacy_summary_file.exists():
            summary_file = legacy_summary_file

        if not summary_file.exists():
            raise FileNotFoundError(f"Analysis summary not found: {summary_file}")

        with open(summary_file, 'r', encoding='utf-8') as f:
            return json.load(f)

    def _aggregate_techniques(self, book_reports: List[Dict]) -> Dict:
        """聚合所有书籍的技巧统计"""
        aggregated = {
            "scene_techniques": {},
            "prose_techniques": {},
            "outline_techniques": {},
            "conflict_types": {"external": 0, "internal": 0, "social": 0},
            "appeal_types": {}
        }

        for report in book_reports:
            stats = report.get("technique_stats", {})

            # 聚合场景技巧
            for tech, count in stats.get("scene_techniques", {}).items():
                aggregated["scene_techniques"][tech] = \
                    aggregated["scene_techniques"].get(tech, 0) + count

            # 聚合文笔技巧
            for tech, count in stats.get("prose_techniques", {}).items():
                aggregated["prose_techniques"][tech] = \
                    aggregated["prose_techniques"].get(tech, 0) + count

            # 聚合大纲技巧
            for tech, count in stats.get("outline_techniques", {}).items():
                aggregated["outline_techniques"][tech] = \
                    aggregated["outline_techniques"].get(tech, 0) + count

            # 聚合冲突类型
            conflict = stats.get("conflict_types", {})
            for conflict_type in ["external", "internal", "social"]:
                aggregated["conflict_types"][conflict_type] += conflict.get(conflict_type, 0)

            # 聚合爽点类型
            for appeal, count in stats.get("appeal_types", {}).items():
                aggregated["appeal_types"][appeal] = \
                    aggregated["appeal_types"].get(appeal, 0) + count

        # 按出现次数排序
        aggregated["scene_techniques"] = dict(
            sorted(aggregated["scene_techniques"].items(), key=lambda x: x[1], reverse=True)
        )
        aggregated["prose_techniques"] = dict(
            sorted(aggregated["prose_techniques"].items(), key=lambda x: x[1], reverse=True)
        )
        aggregated["outline_techniques"] = dict(
            sorted(aggregated["outline_techniques"].items(), key=lambda x: x[1], reverse=True)
        )
        aggregated["appeal_types"] = dict(
            sorted(aggregated["appeal_types"].items(), key=lambda x: x[1], reverse=True)
        )

        return aggregated

    async def _save_synthesis(self, project_id: str, synthesis_content: str) -> Path:
        """保存跨书归纳报告"""
        project_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        synthesis_dir = project_dir / "analysis" / "cross_book"
        synthesis_dir.mkdir(parents=True, exist_ok=True)

        synthesis_file = synthesis_dir / "cross_book_synthesis.md"
        with open(synthesis_file, 'w', encoding='utf-8') as f:
            f.write(synthesis_content)

        with open(project_dir / "cross_book_synthesis.md", 'w', encoding='utf-8') as f:
            f.write(synthesis_content)

        self.logger.info(f"Saved synthesis report to {synthesis_file}")
        return synthesis_file

    async def _save_technique_summary(self, project_id: str, technique_summary: Dict):
        """保存技巧汇总"""
        project_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        synthesis_dir = project_dir / "analysis" / "cross_book"
        synthesis_dir.mkdir(parents=True, exist_ok=True)

        summary_file = synthesis_dir / "technique_summary.json"
        with open(summary_file, 'w', encoding='utf-8') as f:
            json.dump(technique_summary, f, ensure_ascii=False, indent=2)

        with open(project_dir / "technique_summary.json", 'w', encoding='utf-8') as f:
            json.dump(technique_summary, f, ensure_ascii=False, indent=2)

        self.logger.info(f"Saved technique summary to {summary_file}")
