"""
样本导入Agent
负责文本规范化、章节识别、分块处理
"""
import json
from pathlib import Path
from datetime import datetime
from typing import Dict, Any

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from text_processing import TextNormalizer, ChapterDetector, Chunker, CoverageValidator
from config import settings

class SampleImportAgent(BaseAgent):
    """样本导入Agent"""

    def __init__(self):
        super().__init__("SampleImportAgent")
        self.supported_tasks = ["sample_import"]
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

            project_id = request.project_id
            sample_id = request.input_refs.get("sample_id")

            self.logger.info(f"Importing sample: {sample_id}")

            # 2. 读取原始文件
            raw_text = await self._read_raw_sample(project_id, sample_id)

            # 3. 文本规范化
            self.logger.info("Normalizing text...")
            normalized_text, norm_stats = self.normalizer.normalize(raw_text)
            title = self.normalizer.detect_title(normalized_text)

            # 保存规范化文本
            await self._save_normalized_text(project_id, sample_id, normalized_text)

            # 4. 检测章节
            self.logger.info("Detecting chapters...")
            chapters = self.chapter_detector.detect_chapters(normalized_text)

            has_chapters = len(chapters) > 0
            self.logger.info(f"Detected {len(chapters)} chapters")

            # 5. 文本分块
            self.logger.info("Chunking text...")
            if has_chapters:
                chunks = self.chunker.chunk_by_chapters(normalized_text, chapters)
            else:
                chunks = self.chunker.chunk_without_chapters(normalized_text)

            self.logger.info(f"Created {len(chunks)} chunks")

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

            # 7. 保存chunks
            chunks_dir = await self._save_chunks(project_id, sample_id, chunks)

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
                "is_complete": coverage["is_complete"]
            }

            return self._build_response(
                request=request,
                status="success",
                output_refs=output_refs,
                structured_output=structured_output
            )

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

    async def _read_raw_sample(self, project_id: str, sample_id: str) -> str:
        """读取原始样本文件"""
        # 这里需要从Java服务获取文件路径，简化起见直接读取
        # 实际应该通过API获取
        raw_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "samples" / "raw"

        # 查找该sample_id对应的文件
        for file_path in raw_dir.glob("*.md"):
            # 简化：假设文件名包含sample_id或者是第一个文件
            with open(file_path, 'r', encoding='utf-8') as f:
                return f.read()

        for file_path in raw_dir.glob("*.txt"):
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

        for chunk in chunks:
            chunk_file = chunks_dir / f"{chunk['id']}.json"
            with open(chunk_file, 'w', encoding='utf-8') as f:
                json.dump(chunk, f, ensure_ascii=False, indent=2)

        self.logger.info(f"Saved {len(chunks)} chunks to {chunks_dir}")
        return chunks_dir

    async def _save_manifest(self, project_id: str, sample_id: str, manifest: dict):
        """保存manifest"""
        manifest_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "samples" / "manifests"
        manifest_dir.mkdir(parents=True, exist_ok=True)

        manifest_file = manifest_dir / f"{sample_id}_manifest.json"
        with open(manifest_file, 'w', encoding='utf-8') as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)

        self.logger.info(f"Saved manifest to {manifest_file}")
