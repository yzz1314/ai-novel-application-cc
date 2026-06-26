"""Analysis repair agent.

Runs a targeted repair pass for missing or failed chunk analyses. Diagnosis
stays in ``coverage_check``; this agent removes failed artifacts and reuses the
idempotent ``FullTextAnalysisAgent`` retry path.
"""
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Set

from agents.base import BaseAgent
from agents.full_text_analysis_agent import FullTextAnalysisAgent
from config import settings
from llm.client import LLMClient
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from text_processing import CoverageValidator
from utils.logger import get_logger


class AnalysisRepairAgent(BaseAgent):
    """Repair missing or failed per-chunk analysis artifacts."""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("AnalysisRepairAgent")
        self.supported_tasks = ["analysis_repair"]
        self.llm_client = llm_client or LLMClient()
        self.analysis_agent = FullTextAnalysisAgent(llm_client=self.llm_client)
        self.coverage_validator = CoverageValidator()
        self.logger = get_logger("AnalysisRepairAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        self.metrics["start_time"] = datetime.now()
        try:
            await self.validate_request(request)
            project_id = request.project_id
            sample_id = request.input_refs.get("sample_id") or request.parameters.get("sample_id")
            if not sample_id:
                raise ValueError("sample_id is required")

            project_root = self._project_root(project_id)
            before = self._coverage_snapshot(project_root, sample_id)
            requested_chunk_ids = self._normalize_chunk_ids(request.parameters.get("chunk_ids"))
            repair_targets = self._repair_targets(before, requested_chunk_ids)
            removed_failed_files = self._remove_failed_analysis_files(project_root, sample_id, repair_targets)

            analysis_response = None
            if repair_targets:
                analysis_response = await self.analysis_agent.run(AgentRequest(
                    task_id=f"{request.task_id}_full_text_analysis",
                    project_id=project_id,
                    task_type="full_text_analysis",
                    input_refs={"sample_id": sample_id},
                    model_profile_id=request.model_profile_id,
                    skill_ids=request.skill_ids,
                    parameters={
                        "sample_id": sample_id,
                        "skill_name": request.parameters.get("skill_name", ""),
                        "chunk_ids": repair_targets,
                    },
                ))
                self._merge_metrics(analysis_response.metrics)

            after = self._coverage_snapshot(project_root, sample_id)
            remaining_failed_ids = {
                str(item.get("chunk_id"))
                for item in after["failed_chunks"]
                if item.get("chunk_id") is not None
            }
            repaired_chunks = [
                chunk_id for chunk_id in repair_targets
                if chunk_id not in after["missing_analysis_chunks"]
                and chunk_id not in remaining_failed_ids
            ]
            report = {
                "project_id": project_id,
                "sample_id": sample_id,
                "created_at": datetime.now().isoformat(),
                "status": "passed" if after["is_complete"] else "needs_attention",
                "requested_chunk_ids": requested_chunk_ids,
                "repair_targets": repair_targets,
                "removed_failed_files": removed_failed_files,
                "repaired_chunks": repaired_chunks,
                "remaining_missing_chunks": after["missing_analysis_chunks"],
                "remaining_failed_chunks": after["failed_chunks"],
                "before": before,
                "after": after,
                "analysis_task": {
                    "status": analysis_response.status if analysis_response else "skipped",
                    "checkpoint_ref": analysis_response.checkpoint_ref if analysis_response else None,
                    "errors": analysis_response.errors if analysis_response else [],
                    "warnings": analysis_response.warnings if analysis_response else [],
                },
            }
            report_path = self._save_report(project_root, sample_id, report)

            status = "success" if after["is_complete"] else "partial"
            warnings = []
            if status != "success":
                warnings.append({
                    "code": "ANALYSIS_REPAIR_INCOMPLETE",
                    "message": "Some chunks are still missing analysis or still failed; see the repair report.",
                    "retryable": True,
                })

            response = self._build_response(
                request=request,
                status=status,
                output_refs=[
                    self._relative(project_root, report_path),
                    f"analysis/coverage/{sample_id}_coverage.json",
                ],
                structured_output={
                    "sample_id": sample_id,
                    "status": report["status"],
                    "target_count": len(repair_targets),
                    "repaired_count": len(repaired_chunks),
                    "remaining_missing_count": len(after["missing_analysis_chunks"]),
                    "remaining_failed_count": len(after["failed_chunks"]),
                    "report_path": self._relative(project_root, report_path),
                    "coverage_report_path": f"analysis/coverage/{sample_id}_coverage.json",
                },
                errors=analysis_response.errors if analysis_response and status != "success" else [],
                warnings=warnings + (analysis_response.warnings if analysis_response else []),
            )
            response.checkpoint_ref = analysis_response.checkpoint_ref if analysis_response else None
            return response
        except Exception as exc:
            self.logger.error(f"Analysis repair failed: {exc}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "ANALYSIS_REPAIR_ERROR",
                    "message": str(exc),
                    "retryable": True,
                }],
            )

    def _coverage_snapshot(self, project_root: Path, sample_id: str) -> Dict[str, Any]:
        chunks = self._read_chunks(project_root, sample_id)
        chunk_ids = [str(chunk.get("id")) for chunk in chunks if chunk.get("id") is not None]
        analysis_dir = project_root / "analysis" / "per_chunk" / sample_id
        analyzed_ids: Set[str] = set()
        failed_chunks: List[Dict[str, Any]] = []
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
                            "path": self._relative(project_root, file),
                        })
                except Exception as exc:
                    failed_chunks.append({
                        "chunk_id": chunk_id,
                        "error": str(exc),
                        "path": self._relative(project_root, file),
                    })

        missing = [chunk_id for chunk_id in chunk_ids if chunk_id not in analyzed_ids]
        analyzed_count = len([chunk_id for chunk_id in chunk_ids if chunk_id in analyzed_ids])
        snapshot = {
            "total_chunks": len(chunk_ids),
            "analyzed_chunks": analyzed_count,
            "coverage_ratio": analyzed_count / len(chunk_ids) if chunk_ids else 0,
            "missing_analysis_chunks": missing,
            "missing_analysis_count": len(missing),
            "failed_chunks": failed_chunks,
            "failed_chunk_count": len(failed_chunks),
            "is_complete": bool(chunk_ids) and not missing and not failed_chunks,
        }
        self._save_coverage_report(project_root, sample_id, chunks, snapshot)
        return snapshot

    def _repair_targets(self, snapshot: Dict[str, Any], requested: List[str]) -> List[str]:
        missing = set(str(item) for item in snapshot.get("missing_analysis_chunks", []))
        failed = {
            str(item.get("chunk_id"))
            for item in snapshot.get("failed_chunks", [])
            if item.get("chunk_id") is not None
        }
        repairable = missing.union(failed)
        if requested:
            return sorted(str(chunk_id) for chunk_id in requested if str(chunk_id) in repairable)
        return sorted(repairable)

    def _normalize_chunk_ids(self, requested: Any) -> List[str]:
        if not isinstance(requested, list):
            return []
        return sorted({str(item) for item in requested if item is not None and str(item).strip()})

    def _remove_failed_analysis_files(self, project_root: Path, sample_id: str, targets: List[str]) -> List[str]:
        removed = []
        analysis_dir = project_root / "analysis" / "per_chunk" / sample_id
        for chunk_id in targets:
            file = analysis_dir / f"{chunk_id}_analysis.json"
            if not file.exists():
                continue
            try:
                data = self._read_json(file)
                if data.get("error"):
                    file.unlink()
                    removed.append(self._relative(project_root, file))
            except Exception:
                file.unlink(missing_ok=True)
                removed.append(self._relative(project_root, file))
        return removed

    def _save_coverage_report(
            self,
            project_root: Path,
            sample_id: str,
            chunks: List[Dict[str, Any]],
            analysis_snapshot: Dict[str, Any]) -> None:
        manifest = self._read_json(project_root / "samples" / "manifests" / f"{sample_id}_manifest.json")
        text_coverage = self.coverage_validator.validate(int(manifest.get("total_chars") or 0), chunks)
        report = {
            "project_id": project_root.name,
            "sample_id": sample_id,
            "title": manifest.get("title"),
            "created_at": datetime.now().isoformat(),
            "status": (
                "passed"
                if text_coverage.get("is_complete") and analysis_snapshot["is_complete"]
                else "needs_attention"
            ),
            "thresholds": {
                "text_coverage_ratio": 0.999,
                "analysis_coverage_ratio": 1.0,
            },
            "text_coverage": text_coverage,
            "analysis_coverage": analysis_snapshot,
            "manifest_path": f"samples/manifests/{sample_id}_manifest.json",
            "chunks_path": f"samples/chunks/{sample_id}/",
            "analysis_path": f"analysis/per_chunk/{sample_id}/",
        }
        report_dir = project_root / "analysis" / "coverage"
        report_dir.mkdir(parents=True, exist_ok=True)
        with open(report_dir / f"{sample_id}_coverage.json", "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)

    def _save_report(self, project_root: Path, sample_id: str, report: Dict[str, Any]) -> Path:
        report_dir = project_root / "analysis" / "repairs"
        report_dir.mkdir(parents=True, exist_ok=True)
        report_path = report_dir / f"{sample_id}_analysis_repair_{datetime.now().strftime('%Y%m%d%H%M%S')}.json"
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        return report_path

    def _read_chunks(self, project_root: Path, sample_id: str) -> List[Dict[str, Any]]:
        chunks_dir = project_root / "samples" / "chunks" / sample_id
        if not chunks_dir.exists():
            raise FileNotFoundError(f"Chunks directory not found: {chunks_dir}")
        return [self._read_json(path) for path in sorted(chunks_dir.glob("*.json"))]

    def _read_json(self, path: Path) -> Dict[str, Any]:
        if not path.exists():
            raise FileNotFoundError(f"JSON file not found: {path}")
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)

    def _project_root(self, project_id: str) -> Path:
        return Path(settings.PROJECT_BASE_PATH) / "projects" / project_id

    def _relative(self, project_root: Path, path: Path) -> str:
        return str(path.relative_to(project_root)).replace("\\", "/")

    def _merge_metrics(self, metrics: Dict[str, Any]) -> None:
        self.metrics["llm_calls"] += int(metrics.get("llm_calls") or 0)
        self.metrics["input_tokens"] += int(metrics.get("input_tokens") or 0)
        self.metrics["output_tokens"] += int(metrics.get("output_tokens") or 0)
