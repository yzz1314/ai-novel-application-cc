"""Retrieval index rebuild agent."""
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

from agents.base import BaseAgent
from config import settings
from llm.client import LLMClient
from retrieval.context_builder import ContextBuilder
from retrieval.hybrid_engine import HybridRetrievalEngine
from retrieval.rerank import apply_gateway_rerank, resolve_rerank_backend
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from utils.logger import get_logger


class RetrievalIndexAgent(BaseAgent):
    """Build project retrieval indexes without requiring chapter generation."""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("RetrievalIndexAgent")
        self.supported_tasks = ["retrieval_index"]
        self.llm_client = llm_client or LLMClient()
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
            vector_backend = self._vector_backend(request, builder.project_root)
            embedding_bundle = await self._build_embedding_bundle(
                request,
                query,
                documents,
                vector_backend,
                builder.project_root,
            )
            engine = HybridRetrievalEngine(
                builder.project_root,
                documents,
                graph_context,
                embedding_bundle=embedding_bundle,
                vector_backend=vector_backend,
                vector_config=self._config_values(builder.project_root),
            )
            retrieval = engine.retrieve(query, plan, top_k=top_k)
            rerank_application = await apply_gateway_rerank(
                self.llm_client,
                query,
                retrieval,
                engine,
                top_k,
                model_profile_id=request.model_profile_id,
                requested_backend=self._rerank_backend(request, builder.project_root),
            )
            engine.persist_indexes(cache_status=cache_status)
            benchmark_queries = self._benchmark_queries(request, builder.project_root)
            benchmark_report_path = None
            if self._benchmark_requested(request, builder.project_root):
                benchmark_report = engine.evaluate_benchmark(benchmark_queries, plan, top_k=top_k)
                benchmark_report_path = self._write_benchmark_report(builder.project_root, benchmark_report)
            else:
                benchmark_report = {
                    "status": "skipped",
                    "case_count": 0,
                    "passed_count": 0,
                    "hit_rate": 0,
                    "mean_reciprocal_rank": 0,
                    "average_quality_score": 0,
                }
            builder._save_index_summary(documents, cache_status=cache_status)
            citation_budget = builder.preview_citation_budget(retrieval.get("results", []))
            model_gateway = await self._probe_model_gateway(request, query, retrieval.get("results", []))
            model_gateway["embedding_index"] = embedding_bundle.get("metadata", {})
            model_gateway["rerank_application"] = rerank_application
            benchmark_summary = {
                "status": benchmark_report.get("status"),
                "case_count": benchmark_report.get("case_count"),
                "passed_count": benchmark_report.get("passed_count"),
                "hit_rate": benchmark_report.get("hit_rate"),
                "mean_reciprocal_rank": benchmark_report.get("mean_reciprocal_rank"),
                "average_quality_score": benchmark_report.get("average_quality_score"),
            }
            if benchmark_report_path:
                benchmark_summary["report_path"] = self._relative(builder.project_root, benchmark_report_path)
            artifacts = {
                "bm25_summary": "indexes/bm25/index_summary.json",
                "vector_summary": "indexes/vector/index_summary.json",
                "hybrid_summary": "indexes/hybrid/index_summary.json",
                "report": "indexes/retrieval_index_report.json",
            }
            if benchmark_report_path:
                artifacts["benchmark_report"] = "indexes/retrieval_benchmark_report.json"

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
                "benchmark": benchmark_summary,
                "vector_index": retrieval.get("vector_index", {}),
                "rerank": retrieval.get("rerank", rerank_application),
                "model_gateway": model_gateway,
                "top_results": [
                    {
                        "doc_id": item.get("doc_id"),
                        "source_type": item.get("source_type"),
                        "title": item.get("title"),
                        "retrieval_sources": item.get("retrieval_sources"),
                        "fusion_score": item.get("fusion_score"),
                        "rerank_score": item.get("rerank_score"),
                        "rerank_backend": item.get("rerank_backend"),
                    }
                    for item in retrieval.get("results", [])[:top_k]
                ],
                "artifacts": artifacts,
            }
            report_path = self._write_report(builder.project_root, report)
            self._write_hybrid_summary(builder.project_root, report)
            output_refs = [
                "indexes/bm25/index_summary.json",
                "indexes/vector/index_summary.json",
                "indexes/hybrid/index_summary.json",
                self._relative(builder.project_root, report_path),
            ]
            if benchmark_report_path:
                output_refs.append(self._relative(builder.project_root, benchmark_report_path))
            structured_output = {
                "document_count": report["document_count"],
                "source_counts": report["source_counts"],
                "stats": report["stats"],
                "cache_status": report["cache_status"],
                "quality_evaluation": report["quality_evaluation"],
                "citation_budget": report["citation_budget"],
                "benchmark": report["benchmark"],
                "vector_index": report["vector_index"],
                "rerank": report["rerank"],
                "model_gateway": report["model_gateway"],
                "report_path": self._relative(builder.project_root, report_path),
                "bm25_summary_path": "indexes/bm25/index_summary.json",
                "vector_summary_path": "indexes/vector/index_summary.json",
                "hybrid_summary_path": "indexes/hybrid/index_summary.json",
            }
            if benchmark_report_path:
                structured_output["benchmark_report_path"] = self._relative(builder.project_root, benchmark_report_path)

            return self._build_response(
                request=request,
                status="success",
                output_refs=output_refs,
                structured_output=structured_output,
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

    def _benchmark_queries(self, request: AgentRequest, project_root: Path) -> list:
        raw = (
            request.parameters.get("benchmark_queries")
            or request.parameters.get("benchmarkQueries")
            or request.parameters.get("queries")
        )
        if isinstance(raw, list):
            return raw
        if isinstance(raw, dict):
            return raw.get("queries") or raw.get("cases") or []
        if isinstance(raw, str):
            try:
                parsed = json.loads(raw)
                if isinstance(parsed, list):
                    return parsed
                if isinstance(parsed, dict):
                    return parsed.get("queries") or parsed.get("cases") or []
            except json.JSONDecodeError:
                pass

        benchmark_file = project_root / "indexes" / "retrieval_benchmark.json"
        if benchmark_file.exists():
            try:
                data = json.loads(benchmark_file.read_text(encoding="utf-8"))
                if isinstance(data, list):
                    return data
                if isinstance(data, dict):
                    return data.get("queries") or data.get("cases") or []
            except json.JSONDecodeError:
                self.logger.warning("Invalid retrieval benchmark file: %s", benchmark_file)
        return []

    def _benchmark_requested(self, request: AgentRequest, project_root: Path) -> bool:
        has_inline_cases = any(
            key in request.parameters
            for key in ["benchmark_queries", "benchmarkQueries", "queries"]
        )
        benchmark_file = project_root / "indexes" / "retrieval_benchmark.json"
        return self._truthy(request.parameters.get("run_benchmark")) or has_inline_cases or benchmark_file.exists()

    def _source_counts(self, documents) -> Dict[str, int]:
        counts: Dict[str, int] = {}
        for doc in documents:
            counts[doc.source_type] = counts.get(doc.source_type, 0) + 1
        return counts

    async def _build_embedding_bundle(
            self,
            request: AgentRequest,
            query: str,
            documents: list,
            vector_backend: str = "auto",
            project_root: Optional[Path] = None) -> Dict[str, Any]:
        vector_documents = [doc for doc in documents if str(getattr(doc, "text", "") or "").strip()]
        metadata: Dict[str, Any] = {
            "enabled": self._embedding_index_enabled(request, project_root),
            "status": "skipped",
            "vector_mode": "hash_fallback",
            "vector_backend": vector_backend,
            "vector_config": self._config_values(project_root) if project_root is not None else {},
            "document_count": len(vector_documents),
        }
        if not metadata["enabled"]:
            metadata["reason"] = "model_embeddings_not_requested"
            return {"metadata": metadata}

        texts = [query] + [doc.text for doc in vector_documents]
        try:
            response = await self.llm_client.embed_texts(
                texts,
                model_profile_id=request.model_profile_id,
                allow_local_fallback=True,
            )
        except Exception as exc:
            metadata.update({
                "status": "failed",
                "reason": "embedding_gateway_error",
                "error": str(exc),
            })
            return {"metadata": metadata}

        embeddings = response.get("embeddings") or []
        gateway = response.get("model_gateway") or {}
        usage = response.get("usage") or {}
        query_vectors = embeddings[:1]
        document_vectors = embeddings[1:]
        dimensions = len(embeddings[0]) if embeddings else 0
        local_fallback = bool(gateway.get("local_fallback"))
        vector_mode = "local_embedding_fallback" if local_fallback else "model_embedding"
        if len(document_vectors) != len(vector_documents):
            metadata.update({
                "status": "failed",
                "reason": "embedding_count_mismatch",
                "vector_mode": "hash_fallback",
                "embedding_count": len(embeddings),
                "expected_embedding_count": len(vector_documents) + 1,
            })
            return {"metadata": metadata}

        metadata.update({
            "status": gateway.get("status", "success"),
            "vector_mode": vector_mode,
            "vector_backend": vector_backend,
            "operation": gateway.get("operation"),
            "model_profile_id": usage.get("model_profile_id") or gateway.get("model_profile_id"),
            "model_role": usage.get("model_role") or gateway.get("model_role"),
            "model": usage.get("model") or gateway.get("model"),
            "provider": usage.get("provider") or gateway.get("provider"),
            "mock": bool(usage.get("mock") or gateway.get("mock")),
            "local_fallback": local_fallback,
            "fallback_used": bool(usage.get("fallback_used")),
            "fallback_attempts": usage.get("fallback_attempts", 0),
            "fallback_errors": usage.get("fallback_errors", []),
            "dimensions": dimensions,
            "embedding_count": len(embeddings),
            "document_vector_count": len(document_vectors),
            "query_vector_count": len(query_vectors),
            "estimated_cost_usd": usage.get("estimated_cost_usd"),
            "gateway_latency_ms": usage.get("gateway_latency_ms"),
        })
        return {
            "query_vectors": query_vectors,
            "document_vectors": document_vectors,
            "metadata": metadata,
        }

    def _embedding_index_enabled(self, request: AgentRequest, project_root: Optional[Path] = None) -> bool:
        explicit = request.parameters.get("use_model_embeddings")
        if explicit is None:
            explicit = request.parameters.get("useModelEmbeddings")
        if explicit is not None:
            return self._truthy(explicit)
        if project_root is not None:
            configured = self._config_value(project_root, "use_model_embeddings", "useModelEmbeddings")
            if configured is not None:
                return self._truthy(configured)
        if self._truthy(request.parameters.get("probe_model_gateway")):
            return True
        if not request.model_profile_id:
            return False
        try:
            config = self.llm_client._select_profile_model(request.model_profile_id, "embedding")
        except Exception:
            return False
        return bool(config and not config.get("mock"))

    def _vector_backend(self, request: AgentRequest, project_root: Path) -> str:
        explicit = (
            request.parameters.get("vector_backend")
            or request.parameters.get("vectorBackend")
            or request.parameters.get("vector_store")
            or request.parameters.get("vectorStore")
        )
        if explicit:
            return self._normalize_vector_backend(explicit)
        config_path = project_root / "indexes" / "retrieval_config.json"
        if config_path.exists():
            configured = self._config_value(project_root, "vector_backend", "vectorBackend")
            if configured:
                return self._normalize_vector_backend(configured)
        return "auto"

    def _config_value(self, project_root: Path, *keys: str) -> Any:
        config = self._config_values(project_root)
        for key in keys:
            if key in config:
                return config.get(key)
        return None

    def _config_values(self, project_root: Path) -> Dict[str, Any]:
        config_path = project_root / "indexes" / "retrieval_config.json"
        if not config_path.exists():
            return {}
        try:
            config = json.loads(config_path.read_text(encoding="utf-8"))
            return config if isinstance(config, dict) else {}
        except json.JSONDecodeError:
            self.logger.warning("Invalid retrieval config: %s", config_path)
            return {}

    def _normalize_vector_backend(self, value: Any) -> str:
        text = str(value or "auto").strip().lower()
        if text in {"lancedb", "lance", "lance_db"}:
            return "lancedb"
        if text in {"pgvector", "postgres", "postgresql", "postgres_vector", "pg_vector"}:
            return "pgvector"
        if text in {"memory", "local", "hash", "hash_vector", "in_memory"}:
            return "memory"
        return "auto"

    def _rerank_backend(self, request: AgentRequest, project_root: Path) -> str:
        return resolve_rerank_backend(
            self.llm_client,
            request.model_profile_id,
            explicit=request.parameters,
            config=self._config_values(project_root),
        )

    async def _probe_model_gateway(
            self,
            request: AgentRequest,
            query: str,
            results: list) -> Dict[str, Any]:
        enabled = self._truthy(request.parameters.get("probe_model_gateway"))
        gateway = {
            "enabled": enabled,
            "embedding_probe": {"status": "skipped"},
            "rerank_probe": {"status": "skipped"},
        }
        if not enabled:
            return gateway

        sample_texts = [query] + [
            str(item.get("snippet") or item.get("title") or "")
            for item in results[:2]
            if item.get("snippet") or item.get("title")
        ]
        try:
            embedding = await self.llm_client.embed_texts(
                sample_texts[:3],
                model_profile_id=request.model_profile_id,
                allow_local_fallback=True,
            )
            gateway["embedding_probe"] = self._gateway_probe_summary(embedding, "embeddings")
        except Exception as exc:
            gateway["embedding_probe"] = {
                "status": "failed",
                "error": str(exc),
            }

        try:
            rerank = await self.llm_client.rerank(
                query,
                [
                    {
                        "doc_id": item.get("doc_id"),
                        "title": item.get("title"),
                        "text": item.get("snippet") or item.get("title") or "",
                    }
                    for item in results[:5]
                ],
                top_k=min(3, len(results[:5]) or 1),
                model_profile_id=request.model_profile_id,
                allow_local_fallback=True,
            )
            gateway["rerank_probe"] = self._gateway_probe_summary(rerank, "results")
        except Exception as exc:
            gateway["rerank_probe"] = {
                "status": "failed",
                "error": str(exc),
            }
        return gateway

    def _gateway_probe_summary(self, response: Dict[str, Any], result_key: str) -> Dict[str, Any]:
        gateway = response.get("model_gateway") or {}
        usage = response.get("usage") or {}
        items = response.get(result_key) or []
        return {
            "status": gateway.get("status", "unknown"),
            "operation": gateway.get("operation"),
            "model": usage.get("model") or gateway.get("model"),
            "model_role": usage.get("model_role") or gateway.get("model_role"),
            "provider": usage.get("provider") or gateway.get("provider"),
            "mock": bool(usage.get("mock") or gateway.get("mock")),
            "local_fallback": bool(gateway.get("local_fallback")),
            "fallback_used": bool(usage.get("fallback_used")),
            "result_count": len(items),
            "dimensions": gateway.get("dimensions"),
            "estimated_cost_usd": usage.get("estimated_cost_usd"),
        }

    def _truthy(self, value: Any) -> bool:
        if isinstance(value, bool):
            return value
        return str(value or "").strip().lower() in {"1", "true", "yes", "on"}

    def _write_report(self, project_root: Path, report: Dict[str, Any]) -> Path:
        output_path = project_root / "indexes" / "retrieval_index_report.json"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        return output_path

    def _write_benchmark_report(self, project_root: Path, report: Dict[str, Any]) -> Path:
        output_path = project_root / "indexes" / "retrieval_benchmark_report.json"
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
                "benchmark": report.get("benchmark", {}),
                "vector_index": report.get("vector_index", {}),
                "rerank": report.get("rerank", {}),
                "model_gateway": report.get("model_gateway", {}),
                "top_results": report["top_results"],
            }, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return summary_path

    def _relative(self, project_root: Path, path: Path) -> str:
        return str(path.relative_to(project_root)).replace("\\", "/")
