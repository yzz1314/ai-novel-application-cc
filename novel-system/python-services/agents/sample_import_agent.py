"""
样本导入Agent
负责文本规范化、章节识别、分块处理
"""
import json
import shutil
from pathlib import Path
from datetime import datetime
from typing import Dict, Any

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from text_processing import TextNormalizer, ChapterDetector, Chunker, CoverageValidator
from config import settings
from utils.logger import get_logger
from utils.checkpoint_manager import CheckpointManager

class SampleImportAgent(BaseAgent):
    """样本导入Agent"""

    IMPORT_STEPS = [
        ("read_raw", "Read raw sample"),
        ("normalize", "Normalize text"),
        ("save_normalized", "Save normalized text"),
        ("detect_chapters", "Detect chapters"),
        ("chunk_text", "Chunk text"),
        ("validate_coverage", "Validate coverage"),
        ("save_chunks", "Save chunks"),
        ("save_manifest", "Save manifest"),
        ("done", "Completed"),
    ]

    def __init__(self):
        super().__init__("SampleImportAgent")
        self.supported_tasks = ["sample_import"]
        self.logger = get_logger("SampleImportAgent")
        self.checkpoints = CheckpointManager()
        self.normalizer = TextNormalizer()
        self.chapter_detector = ChapterDetector()
        self.chunker = Chunker(
            max_chunk_size=settings.MAX_CHUNK_SIZE,
            overlap=settings.CHUNK_OVERLAP
        )
        self.coverage_validator = CoverageValidator()

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行样本导入任务

        流程：
        1. 读取原始文件
        2. 文本规范化
        3. 检测章节
        4. 文本分块
        5. 验证覆盖率
        6. 保存结果
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)
            self._current_request = request

            project_id = request.project_id
            sample_id = request.input_refs.get("sample_id") or request.parameters.get("sample_id")

            self.logger.info(f"Importing sample: {sample_id}")
            checkpoint_ref = self._save_import_checkpoint(request, sample_id, "read_raw")

            # 2. 读取原始文件
            raw_text = await self._read_raw_sample(project_id, sample_id)
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "normalize",
                total_chars=len(raw_text)
            )

            # 3. 文本规范化
            self.logger.info("Normalizing text...")
            normalized_text, norm_stats = self.normalizer.normalize(raw_text)
            title = self.normalizer.detect_title(normalized_text)
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "save_normalized",
                total_chars=len(normalized_text),
                title=title
            )

            # 保存规范化文本
            await self._save_normalized_text(project_id, sample_id, normalized_text)
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "detect_chapters",
                total_chars=len(normalized_text),
                title=title
            )

            # 4. 检测章节
            self.logger.info("Detecting chapters...")
            chapters = self.chapter_detector.detect_chapters(normalized_text)

            has_chapters = len(chapters) > 0
            self.logger.info(f"Detected {len(chapters)} chapters")
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "chunk_text",
                total_chars=len(normalized_text),
                total_chapters=len(chapters),
                title=title
            )

            # 5. 文本分块
            self.logger.info("Chunking text...")
            if has_chapters:
                chunks = self.chunker.chunk_by_chapters(normalized_text, chapters)
            else:
                chunks = self.chunker.chunk_without_chapters(normalized_text)

            self.logger.info(f"Created {len(chunks)} chunks")
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "validate_coverage",
                total_chars=len(normalized_text),
                total_chapters=len(chapters) if has_chapters else 0,
                total_chunks=len(chunks),
                title=title
            )

            # 6. 验证覆盖率
            coverage = self.coverage_validator.validate(len(normalized_text), chunks)
            self.logger.info(f"Coverage ratio: {coverage['coverage_ratio']:.4f}")

            if not coverage["is_complete"]:
                self.logger.warning(f"Incomplete coverage! Missing {len(coverage['missing_ranges'])} ranges")
                # 生成修复chunks
                repair_chunks = self.coverage_validator.generate_repair_chunks(
                    coverage["missing_ranges"],
                    normalized_text
                )
                chunks.extend(repair_chunks)
                self.logger.info(f"Added {len(repair_chunks)} repair chunks")

                # 重新验证
                coverage = self.coverage_validator.validate(len(normalized_text), chunks)
            chunks = self._assign_stable_chunk_ids(chunks)
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "save_chunks",
                total_chars=len(normalized_text),
                total_chapters=len(chapters) if has_chapters else 0,
                total_chunks=len(chunks),
                coverage_ratio=coverage["coverage_ratio"],
                title=title
            )

            # 7. 保存chunks
            self._invalidate_analysis_artifacts(project_id, sample_id)
            chunks_dir = await self._save_chunks(project_id, sample_id, chunks)
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "save_manifest",
                total_chars=len(normalized_text),
                total_chapters=len(chapters) if has_chapters else 0,
                total_chunks=len(chunks),
                coverage_ratio=coverage["coverage_ratio"],
                chunks_dir=str(chunks_dir),
                title=title
            )

            # 8. 保存manifest
            manifest = {
                "sample_id": sample_id,
                "title": title,
                "total_chars": len(normalized_text),
                "total_chapters": len(chapters) if has_chapters else 0,
                "total_chunks": len(chunks),
                "has_chapters": has_chapters,
                "chapters": chapters,
                "coverage": coverage,
                "created_at": datetime.now().isoformat()
            }
            await self._save_manifest(project_id, sample_id, manifest)
            checkpoint_ref = self._save_import_checkpoint(
                request,
                sample_id,
                "done",
                total_chars=len(normalized_text),
                total_chapters=len(chapters) if has_chapters else 0,
                total_chunks=len(chunks),
                coverage_ratio=coverage["coverage_ratio"],
                title=title
            )

            # 9. 构建响应
            output_refs = [
                f"samples/normalized/{sample_id}.txt",
                f"samples/chunks/{sample_id}/",
                f"samples/manifests/{sample_id}_manifest.json"
            ]

            structured_output = {
                "title": title,
                "total_chars": len(normalized_text),
                "total_chapters": len(chapters) if has_chapters else 0,
                "total_chunks": len(chunks),
                "coverage_ratio": coverage["coverage_ratio"],
                "is_complete": coverage["is_complete"],
                "progress": 1.0,
                "progress_percent": 100,
                "progress_stage": "done",
                "checkpoint_ref": checkpoint_ref
            }

            response = self._build_response(
                request=request,
                status="success",
                output_refs=output_refs,
                structured_output=structured_output
            )
            response.checkpoint_ref = checkpoint_ref
            return response

        except Exception as e:
            self.logger.error(f"Sample import failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "IMPORT_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    def _save_import_checkpoint(
            self,
            request: AgentRequest,
            sample_id: str,
            stage: str,
            **details: Any) -> str:
        """Persist staged import progress for task list polling."""
        step_index = next(
            (index for index, (step_stage, _) in enumerate(self.IMPORT_STEPS) if step_stage == stage),
            0
        )
        stage_label = self.IMPORT_STEPS[step_index][1]
        total_steps = len(self.IMPORT_STEPS) - 1
        processed_steps = min(step_index, total_steps)
        progress = processed_steps / total_steps if total_steps else 1.0
        state = {
            "task_type": "sample_import",
            "task_id": request.task_id,
            "project_id": request.project_id,
            "sample_id": sample_id,
            "stage": stage,
            "stage_label": stage_label,
            "total_steps": total_steps,
            "processed_steps": processed_steps,
            "progress": progress,
            "progress_percent": round(progress * 100),
            "progress_unit": "steps",
            "updated_at": datetime.now().isoformat(),
        }
        state.update({key: value for key, value in details.items() if value is not None})
        checkpoint_ref = self.checkpoints.save(
            request.project_id,
            request.task_id,
            "sample_import",
            state
        )
        self.logger.info(f"Saved sample_import checkpoint: {checkpoint_ref}")
        return checkpoint_ref

    async def _read_raw_sample(self, project_id: str, sample_id: str) -> str:
        """读取原始样本文件"""
        file_path = None
        # Prefer the authoritative path passed by Java after upload.
        # Fallback to filename/sample_id based discovery for manually created tasks.
        if hasattr(self, "_current_request"):
            file_path = (
                self._current_request.input_refs.get("file_path")
                or self._current_request.parameters.get("file_path")
            )

        if file_path:
            candidate = Path(file_path)
            if candidate.exists() and candidate.is_file():
                with open(candidate, 'r', encoding='utf-8') as f:
                    return f.read()

        raw_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "samples" / "raw"

        matching_files = [
            path for path in list(raw_dir.glob("*.md")) + list(raw_dir.glob("*.txt"))
            if sample_id and sample_id in path.name
        ]
        all_files = list(raw_dir.glob("*.md")) + list(raw_dir.glob("*.txt"))

        for file_path in matching_files or all_files:
            with open(file_path, 'r', encoding='utf-8') as f:
                return f.read()

        raise FileNotFoundError(f"Raw sample file not found for {sample_id}")

    async def _save_normalized_text(self, project_id: str, sample_id: str, text: str):
        """保存规范化文本"""
        output_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "samples" / "normalized"
        output_dir.mkdir(parents=True, exist_ok=True)

        output_file = output_dir / f"{sample_id}.txt"
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(text)

        self.logger.info(f"Saved normalized text to {output_file}")

    async def _save_chunks(self, project_id: str, sample_id: str, chunks: list) -> Path:
        """保存分块"""
        chunks_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "samples" / "chunks" / sample_id
        chunks_dir.mkdir(parents=True, exist_ok=True)

        for old_file in chunks_dir.glob("*.json"):
            old_file.unlink()

        for chunk in chunks:
            chunk_file = chunks_dir / f"{chunk['id']}.json"
            with open(chunk_file, 'w', encoding='utf-8') as f:
                json.dump(chunk, f, ensure_ascii=False, indent=2)

        self.logger.info(f"Saved {len(chunks)} chunks to {chunks_dir}")
        return chunks_dir

    def _assign_stable_chunk_ids(self, chunks: list) -> list:
        """Assign globally unique chunk IDs after chapter splitting and repair."""
        ordered_chunks = sorted(
            chunks,
            key=lambda chunk: (
                chunk.get("start_offset", 0),
                chunk.get("end_offset", 0),
                chunk.get("chapter_index", 0),
                chunk.get("part_index", 0),
            )
        )
        for index, chunk in enumerate(ordered_chunks):
            chunk["id"] = f"chunk_{index:06d}"
            chunk["chunk_index"] = index
        return ordered_chunks

    def _invalidate_analysis_artifacts(self, project_id: str, sample_id: str):
        """Remove stale analysis artifacts when a sample is re-imported."""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        paths = [
            project_root / "analysis" / "per_chunk" / sample_id,
            project_root / "samples" / "analysis" / sample_id,
            project_root / "analysis" / "coverage" / f"{sample_id}_coverage.json",
            project_root / "analysis" / "per_book" / f"{sample_id}_report.md",
            project_root / "samples" / "reports" / f"{sample_id}_report.md",
        ]

        for path in paths:
            if path.is_dir():
                shutil.rmtree(path)
                self.logger.info(f"Removed stale analysis directory: {path}")
            elif path.exists():
                path.unlink()
                self.logger.info(f"Removed stale analysis artifact: {path}")

    async def _save_manifest(self, project_id: str, sample_id: str, manifest: dict):
        """保存manifest"""
        manifest_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "samples" / "manifests"
        manifest_dir.mkdir(parents=True, exist_ok=True)

        manifest_file = manifest_dir / f"{sample_id}_manifest.json"
        with open(manifest_file, 'w', encoding='utf-8') as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)

        self.logger.info(f"Saved manifest to {manifest_file}")
