"""
覆盖率校验Agent
生成样本文本分块覆盖率与逐块分析覆盖率报告
"""
import json
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Set

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from text_processing import CoverageValidator
from config import settings
from utils.logger import get_logger


class CoverageCheckAgent(BaseAgent):
    """样本覆盖率校验Agent"""

    def __init__(self):
        super().__init__("CoverageCheckAgent")
        self.supported_tasks = ["coverage_check"]
        self.logger = get_logger("CoverageCheckAgent")
        self.coverage_validator = CoverageValidator()

    async def run(self, request: AgentRequest) -> AgentResponse:
        self.metrics["start_time"] = datetime.now()

        try:
            await self.validate_request(request)
            project_id = request.project_id
            sample_id = request.input_refs.get("sample_id") or request.parameters.get("sample_id")
            if not sample_id:
                raise ValueError("sample_id is required")

            manifest = self._read_json(self._manifest_file(project_id, sample_id))
            chunks = self._read_chunks(project_id, sample_id)
            chunk_coverage = self.coverage_validator.validate(
                int(manifest.get("total_chars") or 0),
                chunks
            )
            analysis_report = self._build_analysis_report(project_id, sample_id, chunks)

            thresholds = {
                "text_coverage_ratio": float(request.parameters.get("text_coverage_ratio", 0.999)),
                "analysis_coverage_ratio": float(request.parameters.get("analysis_coverage_ratio", 1.0)),
            }
            passed = (
                chunk_coverage["coverage_ratio"] >= thresholds["text_coverage_ratio"]
                and analysis_report["coverage_ratio"] >= thresholds["analysis_coverage_ratio"]
                and analysis_report["failed_chunk_count"] == 0
            )

            report = {
                "project_id": project_id,
                "sample_id": sample_id,
                "title": manifest.get("title"),
                "created_at": datetime.now().isoformat(),
                "status": "passed" if passed else "needs_attention",
                "thresholds": thresholds,
                "text_coverage": chunk_coverage,
                "analysis_coverage": analysis_report,
                "manifest_path": f"samples/manifests/{sample_id}_manifest.json",
                "chunks_path": f"samples/chunks/{sample_id}/",
                "analysis_path": f"analysis/per_chunk/{sample_id}/",
            }
            report_path = self._save_report(project_id, sample_id, report)

            warnings = []
            if not passed:
                warnings.append({
                    "code": "COVERAGE_NEEDS_ATTENTION",
                    "message": "样本覆盖率未达到阈值，请检查缺失区间或失败分块。"
                })

            return self._build_response(
                request=request,
                status="success" if passed else "partial",
                output_refs=[self._relative(project_id, report_path)],
                structured_output={
                    "sample_id": sample_id,
                    "status": report["status"],
                    "text_coverage_ratio": chunk_coverage["coverage_ratio"],
                    "analysis_coverage_ratio": analysis_report["coverage_ratio"],
                    "missing_range_count": len(chunk_coverage.get("missing_ranges", [])),
                    "missing_analysis_count": analysis_report["missing_analysis_count"],
                    "failed_chunk_count": analysis_report["failed_chunk_count"],
                    "report_path": self._relative(project_id, report_path),
                },
                warnings=warnings
            )
        except Exception as e:
            self.logger.error(f"Coverage check failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "COVERAGE_CHECK_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    def _build_analysis_report(self, project_id: str, sample_id: str, chunks: List[Dict]) -> Dict:
        chunk_ids = [str(chunk.get("id")) for chunk in chunks if chunk.get("id") is not None]
        analysis_dir = self._analysis_dir(project_id, sample_id)
        analyzed_ids: Set[str] = set()
        failed_chunks = []

        if analysis_dir.exists():
            for file in sorted(analysis_dir.glob("*_analysis.json")):
                chunk_id = file.name[:-len("_analysis.json")]
                analyzed_ids.add(chunk_id)
                try:
                    data = self._read_json(file)
                    if data.get("error"):
                        failed_chunks.append({
                            "chunk_id": chunk_id,
                            "error": data.get("error"),
                            "path": self._relative(project_id, file)
                        })
                except Exception as exc:
                    failed_chunks.append({
                        "chunk_id": chunk_id,
                        "error": str(exc),
                        "path": self._relative(project_id, file)
                    })

        missing_ids = [chunk_id for chunk_id in chunk_ids if chunk_id not in analyzed_ids]
        extra_ids = sorted(analyzed_ids.difference(chunk_ids))
        analyzed_count = len([chunk_id for chunk_id in chunk_ids if chunk_id in analyzed_ids])
        total_chunks = len(chunk_ids)

        return {
            "total_chunks": total_chunks,
            "analyzed_chunks": analyzed_count,
            "coverage_ratio": analyzed_count / total_chunks if total_chunks else 0,
            "is_complete": total_chunks > 0 and analyzed_count == total_chunks and not failed_chunks,
            "missing_analysis_count": len(missing_ids),
            "missing_analysis_chunks": missing_ids,
            "failed_chunk_count": len(failed_chunks),
            "failed_chunks": failed_chunks,
            "extra_analysis_chunks": extra_ids,
        }

    def _read_chunks(self, project_id: str, sample_id: str) -> List[Dict]:
        chunks_dir = self._chunks_dir(project_id, sample_id)
        if not chunks_dir.exists():
            raise FileNotFoundError(f"Chunks directory not found: {chunks_dir}")
        return [self._read_json(path) for path in sorted(chunks_dir.glob("*.json"))]

    def _save_report(self, project_id: str, sample_id: str, report: Dict) -> Path:
        report_dir = self._project_root(project_id) / "analysis" / "coverage"
        report_dir.mkdir(parents=True, exist_ok=True)
        report_file = report_dir / f"{sample_id}_coverage.json"
        with open(report_file, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        return report_file

    def _read_json(self, path: Path) -> Dict:
        if not path.exists():
            raise FileNotFoundError(f"JSON file not found: {path}")
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)

    def _project_root(self, project_id: str) -> Path:
        return Path(settings.PROJECT_BASE_PATH) / "projects" / project_id

    def _manifest_file(self, project_id: str, sample_id: str) -> Path:
        return self._project_root(project_id) / "samples" / "manifests" / f"{sample_id}_manifest.json"

    def _chunks_dir(self, project_id: str, sample_id: str) -> Path:
        return self._project_root(project_id) / "samples" / "chunks" / sample_id

    def _analysis_dir(self, project_id: str, sample_id: str) -> Path:
        return self._project_root(project_id) / "analysis" / "per_chunk" / sample_id

    def _relative(self, project_id: str, path: Path) -> str:
        return str(path.relative_to(self._project_root(project_id))).replace("\\", "/")
