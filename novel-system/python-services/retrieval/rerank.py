"""Shared rerank helpers for retrieval rebuilds and chapter context packs."""
import json
from typing import Any, Dict, Iterable, List, Optional


RERANK_BACKEND_KEYS = (
    "rerank_backend",
    "rerankBackend",
    "reranker",
    "reranker_backend",
    "rerankerBackend",
)


def resolve_rerank_backend(
        llm_client,
        model_profile_id: Optional[str],
        explicit: Any = None,
        config: Optional[Dict[str, Any]] = None) -> str:
    """Resolve rules/auto/llm_gateway into the active requested backend."""
    value = _first_present(_values(explicit))
    if value is None and config:
        value = _first_present(config.get(key) for key in RERANK_BACKEND_KEYS)
    if value is None:
        return "rules"
    return normalize_rerank_backend(value, llm_client, model_profile_id)


def normalize_rerank_backend(value: Any, llm_client=None, model_profile_id: Optional[str] = None) -> str:
    text = str(value or "rules").strip().lower()
    if text in {"off", "none", "disabled", "disable", "false", "0"}:
        return "none"
    if text in {"llm", "gateway", "model", "external", "llm_gateway", "model_gateway"}:
        return "llm_gateway"
    if text in {"auto", "default"}:
        return "llm_gateway" if has_external_rerank_profile(llm_client, model_profile_id) else "rules"
    return "rules"


def has_external_rerank_profile(llm_client, model_profile_id: Optional[str]) -> bool:
    if not llm_client or not model_profile_id:
        return False
    try:
        config = llm_client._select_profile_model(model_profile_id, "rerank")
    except Exception:
        return False
    return bool(config and not config.get("mock"))


async def apply_gateway_rerank(
        llm_client,
        query: str,
        retrieval: Dict[str, Any],
        engine,
        top_k: int,
        model_profile_id: Optional[str] = None,
        requested_backend: str = "rules") -> Dict[str, Any]:
    """Optionally replace rule rerank results with LLM Gateway reranked results."""
    requested = requested_backend or "rules"
    plan = retrieval.get("plan") or {}
    base_summary: Dict[str, Any] = {
        "requested": requested,
        "active": "rules" if plan.get("use_rerank", True) else "none",
        "status": "rules",
        "result_count": len(retrieval.get("results") or []),
    }
    retrieval["rerank"] = base_summary
    retrieval.setdefault("stats", {})["rerank_backend"] = base_summary["active"]
    retrieval["stats"]["rerank_status"] = base_summary["status"]

    if not plan.get("use_rerank", True):
        base_summary.update({"active": "none", "status": "disabled", "reason": "use_rerank_disabled"})
        retrieval["stats"].update({"rerank_backend": "none", "rerank_status": "disabled"})
        return base_summary
    if requested in {"rules", "none"}:
        if requested == "none":
            base_summary.update({
                "active": "rules",
                "status": "rules_fallback",
                "reason": "use_rerank_kept_rule_results",
            })
            retrieval["stats"]["rerank_status"] = "rules_fallback"
        return base_summary

    candidates = list(retrieval.get("fused_results") or retrieval.get("results") or [])
    if not candidates:
        base_summary.update({"status": "skipped", "reason": "no_candidates"})
        retrieval["stats"]["rerank_status"] = "skipped"
        return base_summary

    documents = [
        {
            "index": index,
            "doc_id": item.get("doc_id") or f"candidate_{index}",
            "title": item.get("title") or item.get("doc_id") or f"candidate_{index}",
            "text": " ".join([
                str(item.get("title") or ""),
                str(item.get("snippet") or ""),
                json.dumps(item.get("metadata") or {}, ensure_ascii=False),
            ]).strip(),
        }
        for index, item in enumerate(candidates)
    ]
    try:
        response = await llm_client.rerank(
            query,
            documents,
            top_k=min(top_k, len(documents)),
            model_profile_id=model_profile_id,
            allow_local_fallback=True,
        )
        ranked = merge_gateway_rerank(candidates, response.get("results") or [], top_k)
        if not ranked:
            raise ValueError("Rerank gateway returned no mappable candidates")

        retrieval["reranked_results"] = ranked
        retrieval["results"] = ranked
        retrieval["quality_evaluation"] = engine._quality_evaluation(
            query,
            retrieval.get("runs") or {},
            retrieval.get("fused_results") or [],
            ranked,
            top_k,
        )
        gateway = response.get("model_gateway") or {}
        usage = response.get("usage") or {}
        summary = {
            "requested": requested,
            "active": "llm_gateway",
            "status": gateway.get("status") or "success",
            "operation": gateway.get("operation"),
            "model_profile_id": usage.get("model_profile_id") or gateway.get("model_profile_id"),
            "model_role": usage.get("model_role") or gateway.get("model_role"),
            "model": usage.get("model") or gateway.get("model"),
            "provider": usage.get("provider") or gateway.get("provider"),
            "mock": bool(usage.get("mock") or gateway.get("mock")),
            "local_fallback": bool(gateway.get("local_fallback")),
            "fallback_used": bool(usage.get("fallback_used")),
            "fallback_attempts": usage.get("fallback_attempts", 0),
            "fallback_errors": usage.get("fallback_errors", []),
            "estimated_cost_usd": usage.get("estimated_cost_usd"),
            "gateway_latency_ms": usage.get("gateway_latency_ms"),
            "candidate_count": len(candidates),
            "result_count": len(ranked),
        }
        retrieval["rerank"] = summary
        retrieval.setdefault("stats", {}).update({
            "returned_count": len(ranked),
            "quality_score": retrieval["quality_evaluation"].get("score"),
            "quality_status": retrieval["quality_evaluation"].get("status"),
            "rerank_backend": "llm_gateway",
            "rerank_status": summary["status"],
            "rerank_local_fallback": summary["local_fallback"],
        })
        return summary
    except Exception as exc:
        base_summary.update({
            "active": "rules",
            "status": "fallback",
            "reason": "gateway_rerank_error",
            "error": str(exc),
        })
        retrieval["rerank"] = base_summary
        retrieval.setdefault("stats", {}).update({
            "rerank_backend": "rules",
            "rerank_status": "fallback",
        })
        return base_summary


def merge_gateway_rerank(candidates: List[Dict[str, Any]], ranked: List[Dict[str, Any]], top_k: int) -> List[Dict[str, Any]]:
    by_doc_id = {str(item.get("doc_id")): item for item in candidates if item.get("doc_id") is not None}
    by_index = {index: item for index, item in enumerate(candidates)}
    merged = []
    seen = set()
    for rank, item in enumerate(ranked, start=1):
        candidate = None
        doc_id = item.get("doc_id")
        if doc_id is not None:
            candidate = by_doc_id.get(str(doc_id))
        if candidate is None:
            try:
                candidate = by_index.get(int(item.get("index")))
            except (TypeError, ValueError):
                candidate = None
        if not candidate:
            continue
        identity = candidate.get("doc_id") or id(candidate)
        if identity in seen:
            continue
        seen.add(identity)
        reranked_item = dict(candidate)
        model_score = _float(item.get("score"), 0.0)
        reranked_item["rerank_score"] = round(model_score, 6)
        reranked_item["rerank_backend"] = "llm_gateway"
        reranked_item["rerank_features"] = {
            "backend": "llm_gateway",
            "model_score": round(model_score, 6),
            "model_rank": rank,
            "reason": item.get("reason") or "",
        }
        merged.append(reranked_item)
        if len(merged) >= top_k:
            break
    return merged


def _values(value: Any) -> Iterable[Any]:
    if isinstance(value, dict):
        return (value.get(key) for key in RERANK_BACKEND_KEYS)
    return (value,)


def _first_present(values: Iterable[Any]) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def _float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default
