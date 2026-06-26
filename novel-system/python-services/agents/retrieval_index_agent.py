"""Retrieval index rebuild agent."""
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict

from agents.base import BaseAgent
from config import settings
from retrieval.context_builder import ContextBuilder
from retrieval.hybrid_engine import HybridRetrievalEngine
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from utils.logger import get_logger


class RetrievalIndexAgent(BaseAgent):
    """Build project retrieval indexes without requiring chapter generation."""

    def __init__(self):
        super().__init__("RetrievalIndexAgent")
        self.supported_tasks = ["retrieval_index"]
        self.logger = get_logger("RetrievalIndexAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        self.metrics["start_time"] = datetime.now()

        try:
            await self.validate_request(request)
            builder = ContextBuilder(request.project_id)
            query = self._query(request)
            top_k = int(request.parameters.get("top_k", 12))

            documents = builder._load_documents()
            cache_status = builder.cache_status(documents)
            graph_context = builder._graph_context(query)
            plan = builder._default_plan()
            engine = HybridRetrievalEngine(builder.project_root, documents, graph_context)
            engine.persist_indexes(cache_status=cache_status)
            retrieval = engine.retrieve(query, plan, top_k=top_k)
            builder._save_index_summary(documents, cache_status=cache_status)
            citation_budget = builder.preview_citation_budget(retrieval.get("results", []))

            report = {
                "project_id": request.project_id,
                "built_at": datetime.now().isoformat(),
                "cache_status": cache_status,
                "query": query,
                "top_k": top_k,
                "document_count": len(documents),
                "source_counts": self._source_counts(documents),
                "plan": retrieval.get("plan", {}),
                "stats": retrieval.get("stats", {}),
                "quality_evaluation": retrieval.get("quality_evaluation", {}),
                "citation_budget": citation_budget,
                "top_results": [
                    {
                        "doc_id": item.get("doc_id"),
                        "source_type": item.get("source_type"),
                        "title": item.get("title"),
                        "retrieval_sources": item.get("retrieval_sources"),
                        "fusion_score": item.get("fusion_score"),
                        "rerank_score": item.get("rerank_score"),
                    }
                    for item in retrieval.get("results", [])[:top_k]
                ],
                "artifacts": {
                    "bm25_summary": "indexes/bm25/index_summary.json",
                    "vector_summary": "indexes/vector/index_summary.json",
                    "hybrid_summary": "indexes/hybrid/index_summary.json",
                    "report": "indexes/retrieval_index_report.json",
                },
            }
            report_path = self._write_report(builder.project_root, report)
            self._write_hybrid_summary(builder.project_root, report)

            return self._build_response(
                request=request,
                status="success",
                output_refs=[
                    "indexes/bm25/index_summary.json",
                    "indexes/vector/index_summary.json",
                    "indexes/hybrid/index_summary.json",
                    self._relative(builder.project_root, report_path),
                ],
                structured_output={
                    "document_count": report["document_count"],
                    "source_counts": report["source_counts"],
                    "stats": report["stats"],
                    "cache_status": report["cache_status"],
                    "quality_evaluation": report["quality_evaluation"],
                    "citation_budget": report["citation_budget"],
                    "report_path": self._relative(builder.project_root, report_path),
                    "bm25_summary_path": "indexes/bm25/index_summary.json",
                    "vector_summary_path": "indexes/vector/index_summary.json",
                    "hybrid_summary_path": "indexes/hybrid/index_summary.json",
                },
            )
        except Exception as e:
            self.logger.error(f"Retrieval index rebuild failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "RETRIEVAL_INDEX_ERROR",
                    "message": str(e),
                    "retryable": True,
                }],
            )

    def _query(self, request: AgentRequest) -> str:
        query = request.parameters.get("query") or request.user_input
        if query:
            return str(query)
        return "项目设定 人物 伏笔 技法 大纲 章节 样本"

    def _source_counts(self, documents) -> Dict[str, int]:
        counts: Dict[str, int] = {}
        for doc in documents:
            counts[doc.source_type] = counts.get(doc.source_type, 0) + 1
        return counts

    def _write_report(self, project_root: Path, report: Dict[str, Any]) -> Path:
        output_path = project_root / "indexes" / "retrieval_index_report.json"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        return output_path

    def _write_hybrid_summary(self, project_root: Path, report: Dict[str, Any]) -> Path:
        hybrid_dir = project_root / "indexes" / "hybrid"
        hybrid_dir.mkdir(parents=True, exist_ok=True)
        summary_path = hybrid_dir / "index_summary.json"
        summary_path.write_text(
            json.dumps({
                "project_id": report["project_id"],
                "updated_at": report["built_at"],
                "latest_context_pack": None,
                "latest_rebuild_report": report["artifacts"]["report"],
                "cache_status": report.get("cache_status", {}),
                "plan": report["plan"],
                "stats": report["stats"],
                "quality_evaluation": report.get("quality_evaluation", {}),
                "citation_budget": report.get("citation_budget", {}),
                "top_results": report["top_results"],
            }, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return summary_path

    def _relative(self, project_root: Path, path: Path) -> str:
        return str(path.relative_to(project_root)).replace("\\", "/")
