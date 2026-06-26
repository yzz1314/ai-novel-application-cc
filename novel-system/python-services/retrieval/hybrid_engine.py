"""Local hybrid retrieval engine.

This module implements the retrieval shape from the architecture docs without
requiring an external vector database. It creates deterministic hashed vectors
from local artifacts, fuses keyword/vector/graph matches, and records rerank
scores so the system has inspectable retrieval plans and artifacts today.
"""
import hashlib
import json
import math
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from .context_builder import KeywordRetriever, RetrievalDocument


@dataclass
class RetrievalPlan:
    use_vector: bool = True
    vector_filters: Dict[str, Any] = field(default_factory=dict)
    use_keyword: bool = True
    keyword_filters: Dict[str, Any] = field(default_factory=dict)
    use_graph: bool = True
    graph_relation: Optional[str] = None
    graph_hops: int = 2
    use_rerank: bool = True


class HashVectorIndex:
    """Tiny deterministic vector index for local/offline retrieval."""

    def __init__(self, documents: List[RetrievalDocument], tokenizer, dimensions: int = 96):
        self.documents = [doc for doc in documents if doc.text.strip()]
        self.tokenizer = tokenizer
        self.dimensions = dimensions
        self.vectors = [self._embed(doc.text) for doc in self.documents]

    def search(self, query: str, top_k: int = 16, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        query_vector = self._embed(query)
        if not query_vector:
            return []
        results = []
        for index, doc in enumerate(self.documents):
            if filters and not self._matches_filters(doc, filters):
                continue
            score = self._cosine(query_vector, self.vectors[index])
            results.append(self._result(doc, score, "vector"))
        results.sort(key=lambda item: item["score"], reverse=True)
        return results[:top_k]

    def persist(self, output_dir: Path):
        output_dir.mkdir(parents=True, exist_ok=True)
        summary = {
            "updated_at": datetime.now().isoformat(),
            "engine": "hash_vector",
            "dimensions": self.dimensions,
            "document_count": len(self.documents),
            "documents": [
                {
                    "doc_id": doc.doc_id,
                    "source_type": doc.source_type,
                    "path": doc.path,
                    "title": doc.title,
                    "vector_norm": round(math.sqrt(sum(value * value for value in vector)), 6),
                }
                for doc, vector in zip(self.documents, self.vectors)
            ],
        }
        (output_dir / "index_summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )

    def _embed(self, text: str) -> List[float]:
        vector = [0.0] * self.dimensions
        tokens = self.tokenizer(text)
        if not tokens:
            return vector
        counts = Counter(tokens)
        for token, count in counts.items():
            digest = hashlib.md5(token.encode("utf-8")).hexdigest()
            index = int(digest[:8], 16) % self.dimensions
            sign = 1 if int(digest[8:10], 16) % 2 == 0 else -1
            vector[index] += sign * (1.0 + math.log(count))
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            return vector
        return [value / norm for value in vector]

    def _cosine(self, left: List[float], right: List[float]) -> float:
        return sum(a * b for a, b in zip(left, right))

    def _matches_filters(self, doc: RetrievalDocument, filters: Dict[str, Any]) -> bool:
        source_types = filters.get("source_type") or filters.get("source_types") or filters.get("type")
        if source_types:
            allowed = set(source_types if isinstance(source_types, list) else [source_types])
            if doc.source_type not in allowed:
                return False
        return True

    def _result(self, doc: RetrievalDocument, score: float, source: str) -> Dict[str, Any]:
        return {
            "doc_id": doc.doc_id,
            "source_type": doc.source_type,
            "path": doc.path,
            "title": doc.title,
            "score": round(score, 4),
            "snippet": doc.text[:240] + ("..." if len(doc.text) > 240 else ""),
            "metadata": doc.metadata,
            "retrieval_source": source,
        }


class HybridRetrievalEngine:
    def __init__(self, project_root: Path, documents: List[RetrievalDocument], graph_context: Dict[str, Any]):
        self.project_root = project_root
        self.documents = documents
        self.keyword = KeywordRetriever(documents)
        self.vector = HashVectorIndex(documents, self.keyword._tokenize)
        self.graph_context = graph_context or {}

    def retrieve(self, query: str, plan: RetrievalPlan, top_k: int = 10) -> Dict[str, Any]:
        runs: Dict[str, List[Dict[str, Any]]] = {}
        if plan.use_keyword:
            runs["keyword"] = [
                {**item, "retrieval_source": "keyword"}
                for item in self.keyword.search(query, top_k=top_k * 2, filters=plan.keyword_filters)
            ]
        if plan.use_vector:
            runs["vector"] = self.vector.search(query, top_k=top_k * 2, filters=plan.vector_filters)
        if plan.use_graph:
            runs["graph"] = self._graph_results(query, top_k=top_k)

        fused = self._rrf_fuse(runs, top_k=top_k * 2)
        reranked = self._rerank(query, fused, top_k=top_k) if plan.use_rerank else fused[:top_k]

        return {
            "plan": asdict(plan),
            "runs": runs,
            "fused_results": fused,
            "reranked_results": reranked,
            "results": reranked,
            "stats": {
                "keyword_count": len(runs.get("keyword", [])),
                "vector_count": len(runs.get("vector", [])),
                "graph_count": len(runs.get("graph", [])),
                "fused_count": len(fused),
                "returned_count": len(reranked),
            },
        }

    def persist_indexes(self):
        self.vector.persist(self.project_root / "indexes" / "vector")

    def _graph_results(self, query: str, top_k: int) -> List[Dict[str, Any]]:
        query_tokens = set(self.keyword._tokenize(query))
        results = []
        for node in self.graph_context.get("matched_nodes", []):
            text = " ".join([
                str(node.get("name", "")),
                str(node.get("node_type", "")),
                str(node.get("description", "")),
            ])
            node_tokens = set(self.keyword._tokenize(text))
            overlap = len(query_tokens & node_tokens)
            score = overlap + (0.2 if node.get("name") else 0)
            if score <= 0:
                continue
            results.append({
                "doc_id": f"graph:{node.get('node_id') or node.get('name')}",
                "source_type": "graph_node",
                "path": self.graph_context.get("path"),
                "title": node.get("name") or node.get("node_id"),
                "score": round(score, 4),
                "snippet": node.get("description", ""),
                "metadata": node,
                "retrieval_source": "graph",
            })
        results.sort(key=lambda item: item["score"], reverse=True)
        return results[:top_k]

    def _rrf_fuse(self, runs: Dict[str, List[Dict[str, Any]]], top_k: int) -> List[Dict[str, Any]]:
        rrf_k = 60
        by_id: Dict[str, Dict[str, Any]] = {}
        scores: Dict[str, float] = defaultdict(float)
        sources: Dict[str, List[str]] = defaultdict(list)

        for source, results in runs.items():
            for rank, item in enumerate(results, start=1):
                doc_id = item["doc_id"]
                by_id.setdefault(doc_id, dict(item))
                scores[doc_id] += 1.0 / (rrf_k + rank)
                sources[doc_id].append(source)

        fused = []
        for doc_id, item in by_id.items():
            fused_item = dict(item)
            fused_item["fusion_score"] = round(scores[doc_id], 6)
            fused_item["retrieval_sources"] = sorted(set(sources[doc_id]))
            fused.append(fused_item)
        fused.sort(key=lambda item: item["fusion_score"], reverse=True)
        return fused[:top_k]

    def _rerank(self, query: str, results: List[Dict[str, Any]], top_k: int) -> List[Dict[str, Any]]:
        query_tokens = set(self.keyword._tokenize(query))
        reranked = []
        for item in results:
            text = " ".join([
                str(item.get("title", "")),
                str(item.get("snippet", "")),
                json.dumps(item.get("metadata", {}), ensure_ascii=False),
            ])
            item_tokens = set(self.keyword._tokenize(text))
            overlap = len(query_tokens & item_tokens)
            coverage = overlap / max(1, len(query_tokens))
            source_bonus = len(item.get("retrieval_sources", [])) * 0.02
            rerank_score = float(item.get("fusion_score", 0)) + coverage + source_bonus
            reranked_item = dict(item)
            reranked_item["rerank_score"] = round(rerank_score, 6)
            reranked_item["rerank_features"] = {
                "query_token_overlap": overlap,
                "query_token_coverage": round(coverage, 4),
                "source_bonus": round(source_bonus, 4),
            }
            reranked.append(reranked_item)
        reranked.sort(key=lambda item: item["rerank_score"], reverse=True)
        return reranked[:top_k]
