"""Lightweight retrieval and context-pack builder.

This is the first concrete retrieval layer for chapter writing. It keeps the
implementation local-file based so it works with the current workspace
architecture, while leaving a clean place to add LanceDB/vector/rerank later.
"""
import json
import math
import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

try:
    import jieba
except Exception:  # pragma: no cover - optional runtime convenience
    jieba = None

from config import settings


@dataclass
class RetrievalDocument:
    doc_id: str
    source_type: str
    path: str
    title: str
    text: str
    metadata: Dict[str, Any] = field(default_factory=dict)


class KeywordRetriever:
    """Small BM25-style keyword retriever over project artifacts."""

    def __init__(self, documents: List[RetrievalDocument]):
        self.documents = [doc for doc in documents if doc.text.strip()]
        self.doc_tokens = [self._tokenize(doc.text) for doc in self.documents]
        self.doc_lengths = [max(1, len(tokens)) for tokens in self.doc_tokens]
        self.avg_doc_length = sum(self.doc_lengths) / len(self.doc_lengths) if self.doc_lengths else 1
        self.document_frequency = self._document_frequency(self.doc_tokens)

    def search(self, query: str, top_k: int = 8, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        query_tokens = self._tokenize(query)
        if not query_tokens:
            return []

        results = []
        for index, doc in enumerate(self.documents):
            if filters and not self._matches_filters(doc, filters):
                continue
            score = self._bm25(query_tokens, self.doc_tokens[index], self.doc_lengths[index])
            if score <= 0:
                continue
            results.append({
                "doc_id": doc.doc_id,
                "source_type": doc.source_type,
                "path": doc.path,
                "title": doc.title,
                "score": round(score, 4),
                "snippet": self._snippet(doc.text, query_tokens),
                "metadata": doc.metadata,
            })

        results.sort(key=lambda item: item["score"], reverse=True)
        return results[:top_k]

    def _bm25(self, query_tokens: List[str], doc_tokens: List[str], doc_length: int) -> float:
        token_counts = Counter(doc_tokens)
        score = 0.0
        k1 = 1.5
        b = 0.75
        total_docs = max(1, len(self.documents))

        for token in query_tokens:
            frequency = token_counts.get(token, 0)
            if frequency == 0:
                continue
            doc_frequency = self.document_frequency.get(token, 0)
            idf = math.log(1 + (total_docs - doc_frequency + 0.5) / (doc_frequency + 0.5))
            denominator = frequency + k1 * (1 - b + b * doc_length / self.avg_doc_length)
            score += idf * (frequency * (k1 + 1)) / denominator
        return score

    def _document_frequency(self, token_lists: List[List[str]]) -> Counter:
        counter: Counter = Counter()
        for tokens in token_lists:
            counter.update(set(tokens))
        return counter

    def _tokenize(self, text: str) -> List[str]:
        text = text.lower()
        if jieba:
            tokens = [token.strip() for token in jieba.cut(text) if token.strip()]
        else:
            tokens = re.findall(r"[\w\u4e00-\u9fff]+", text)

        expanded: List[str] = []
        for token in tokens:
            if len(token) <= 1 and not re.match(r"[\u4e00-\u9fff]", token):
                continue
            expanded.append(token)
            if re.search(r"[\u4e00-\u9fff]", token) and len(token) > 2:
                expanded.extend(token[i:i + 2] for i in range(len(token) - 1))
        return expanded

    def _matches_filters(self, doc: RetrievalDocument, filters: Dict[str, Any]) -> bool:
        source_types = filters.get("source_type") or filters.get("source_types")
        if source_types and doc.source_type not in set(source_types):
            return False
        return True

    def _snippet(self, text: str, query_tokens: List[str], length: int = 240) -> str:
        lowered = text.lower()
        first_match = min(
            (lowered.find(token) for token in query_tokens if lowered.find(token) >= 0),
            default=0
        )
        start = max(0, first_match - length // 3)
        snippet = text[start:start + length].strip()
        return snippet + ("..." if start + length < len(text) else "")


class ContextBuilder:
    """Builds a reusable context pack for downstream writing agents."""

    def __init__(self, project_id: str):
        self.project_id = project_id
        self.project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id

    async def build_chapter_context(
        self,
        book_id: str,
        volume_number: int,
        chapter_number: int,
        chapter_outline: Any,
        previous_context: str,
        top_k: int = 8,
    ) -> Dict[str, Any]:
        query = self._chapter_query(chapter_outline, previous_context)
        documents = self._load_documents()
        graph_context = self._graph_context(query)
        memory_context = self._memory_context()
        retrieval_plan = self._default_plan()
        from .hybrid_engine import HybridRetrievalEngine
        hybrid_engine = HybridRetrievalEngine(self.project_root, documents, graph_context)
        hybrid_engine.persist_indexes()
        hybrid_retrieval = hybrid_engine.retrieve(query, retrieval_plan, top_k=top_k)
        keyword_results = hybrid_retrieval.get("runs", {}).get("keyword", [])[:top_k]
        retrieval_results = hybrid_retrieval.get("results", [])
        context_pack = {
            "project_id": self.project_id,
            "book_id": book_id,
            "volume_number": volume_number,
            "chapter_number": chapter_number,
            "query": query,
            "built_at": datetime.now().isoformat(),
            "sources": {
                "documents_indexed": len(documents),
                "keyword_results": len(keyword_results),
                "vector_results": hybrid_retrieval.get("stats", {}).get("vector_count", 0),
                "graph_results": hybrid_retrieval.get("stats", {}).get("graph_count", 0),
                "reranked_results": hybrid_retrieval.get("stats", {}).get("returned_count", 0),
                "memory_files": len(memory_context.get("files", [])),
                "graph_nodes": len(graph_context.get("matched_nodes", [])),
            },
            "previous_context": previous_context,
            "memory": memory_context,
            "graph": graph_context,
            "retrieval_plan": hybrid_retrieval.get("plan", {}),
            "hybrid_retrieval": hybrid_retrieval,
            "keyword_results": keyword_results,
            "retrieval_results": retrieval_results,
        }

        context_pack["prompt_section"] = self.format_prompt_section(context_pack)
        context_pack["path"] = str(self._save_context_pack(context_pack))
        self._save_index_summary(documents)
        self._save_hybrid_summary(context_pack)
        return context_pack

    def _default_plan(self):
        from .hybrid_engine import RetrievalPlan
        config_path = self.project_root / "indexes" / "retrieval_config.json"
        data = self._read_json(config_path) if config_path.exists() else {}
        return RetrievalPlan(
            use_vector=bool(data.get("use_vector", True)),
            vector_filters=data.get("vector_filters") or {},
            use_keyword=bool(data.get("use_keyword", True)),
            keyword_filters=data.get("keyword_filters") or {},
            use_graph=bool(data.get("use_graph", True)),
            graph_relation=data.get("graph_relation"),
            graph_hops=int(data.get("graph_hops", 2)),
            use_rerank=bool(data.get("use_rerank", True)),
        )

    def format_prompt_section(self, context_pack: Dict[str, Any]) -> str:
        parts = ["## 检索上下文包（写作时必须参考）"]

        memory = context_pack.get("memory", {})
        if memory.get("canon"):
            parts.append("### Canon与记忆\n" + memory["canon"][:1200])
        if memory.get("characters"):
            parts.append("### 人物/关系/认知记忆\n" + memory["characters"][:1000])

        graph_nodes = context_pack.get("graph", {}).get("matched_nodes", [])
        if graph_nodes:
            node_lines = [
                f"- {node.get('name')} ({node.get('node_type')}): {node.get('description', '')}"
                for node in graph_nodes[:8]
            ]
            parts.append("### 图谱相关实体\n" + "\n".join(node_lines))

        retrieval_results = context_pack.get("retrieval_results", [])
        if retrieval_results:
            result_lines = [
                f"- [{item['source_type']}] {item['title']} | {item['snippet']}"
                for item in retrieval_results[:8]
            ]
            parts.append("### 样本/报告/技巧检索结果\n" + "\n".join(result_lines))

        parts.append("### 使用原则\n- 不得违背 Project Soul 和 Canon。\n- 检索结果只作为技法和事实参考，不得照搬样本文字。\n- 优先保证本章大纲边界，不提前泄露后续章纲。")
        return "\n\n".join(parts)

    def _chapter_query(self, chapter_outline: Any, previous_context: str) -> str:
        fields = [
            getattr(chapter_outline, "chapter_title", ""),
            getattr(chapter_outline, "plot_goal", ""),
            getattr(chapter_outline, "character_development", ""),
            getattr(chapter_outline, "info_reveal", ""),
            getattr(chapter_outline, "conflict", ""),
            getattr(chapter_outline, "appeal_point", ""),
            getattr(chapter_outline, "suspense", ""),
            getattr(chapter_outline, "connect_previous", ""),
            getattr(chapter_outline, "lead_to_next", ""),
            previous_context[:500],
        ]
        return "\n".join(str(field) for field in fields if field)

    def _load_documents(self) -> List[RetrievalDocument]:
        documents: List[RetrievalDocument] = []
        documents.extend(self._sample_chunk_documents())
        documents.extend(self._markdown_documents("analysis/per_book", "book_report"))
        documents.extend(self._markdown_documents("analysis/cross_book", "cross_book_report"))
        documents.extend(self._markdown_documents("skills/local", "skill"))
        documents.extend(self._markdown_documents("memory", "memory"))
        return documents

    def _sample_chunk_documents(self) -> List[RetrievalDocument]:
        chunk_root = self.project_root / "samples" / "chunks"
        if not chunk_root.exists():
            return []

        documents = []
        for path in sorted(chunk_root.glob("*/*.json")):
            data = self._read_json(path)
            text = data.get("text") or data.get("content") or ""
            chunk_id = data.get("chunk_id") or path.stem
            documents.append(RetrievalDocument(
                doc_id=f"chunk:{chunk_id}",
                source_type="sample_chunk",
                path=self._relative(path),
                title=str(chunk_id),
                text=text,
                metadata={
                    "sample_id": data.get("sample_id") or path.parent.name,
                    "chunk_id": chunk_id,
                },
            ))
        return documents

    def _markdown_documents(self, relative_dir: str, source_type: str) -> List[RetrievalDocument]:
        root = self.project_root / relative_dir
        if not root.exists():
            return []
        documents = []
        for path in sorted(root.glob("*.md")):
            text = self._read_text(path)
            documents.append(RetrievalDocument(
                doc_id=f"{source_type}:{path.stem}",
                source_type=source_type,
                path=self._relative(path),
                title=path.stem,
                text=text,
            ))
        return documents

    def _memory_context(self) -> Dict[str, Any]:
        memory_root = self.project_root / "memory"
        files = []
        for name in ["canon", "characters", "relationships", "cognition", "foreshadowing", "timeline"]:
            path = memory_root / f"{name}.md"
            if path.exists():
                files.append({"type": name, "path": self._relative(path), "content": self._read_text(path)})

        def section(*names: str) -> str:
            selected = [item["content"] for item in files if item["type"] in set(names)]
            return "\n\n".join(selected)

        return {
            "files": [{"type": item["type"], "path": item["path"]} for item in files],
            "canon": section("canon", "timeline"),
            "characters": section("characters", "relationships", "cognition"),
            "foreshadowing": section("foreshadowing"),
        }

    def _graph_context(self, query: str) -> Dict[str, Any]:
        graph_file = self.project_root / "graph" / "graph.json"
        if not graph_file.exists():
            return {"path": None, "matched_nodes": [], "matched_edges": []}

        graph = self._read_json(graph_file)
        query_tokens = set(KeywordRetriever([])._tokenize(query))
        matched_nodes = []
        for node in graph.get("nodes", []):
            node_text = " ".join([
                str(node.get("name", "")),
                str(node.get("node_type", "")),
                json.dumps(node.get("properties", {}), ensure_ascii=False),
            ])
            node_tokens = set(KeywordRetriever([])._tokenize(node_text))
            if query_tokens & node_tokens or node.get("name"):
                properties = node.get("properties", {})
                matched_nodes.append({
                    "node_id": node.get("node_id"),
                    "node_type": node.get("node_type"),
                    "name": node.get("name"),
                    "description": properties.get("description", ""),
                })

        matched_ids = {node["node_id"] for node in matched_nodes}
        matched_edges = [
            edge for edge in graph.get("edges", [])
            if edge.get("source_id") in matched_ids or edge.get("target_id") in matched_ids
        ]
        return {
            "path": self._relative(graph_file),
            "matched_nodes": matched_nodes[:12],
            "matched_edges": matched_edges[:20],
        }

    def _save_context_pack(self, context_pack: Dict[str, Any]) -> Path:
        output_dir = self.project_root / "indexes" / "bm25" / "context_packs"
        output_dir.mkdir(parents=True, exist_ok=True)
        file_name = (
            f"{context_pack['book_id']}_v{context_pack['volume_number']}"
            f"_c{context_pack['chapter_number']}.json"
        )
        path = output_dir / file_name
        with open(path, "w", encoding="utf-8") as f:
            json.dump(context_pack, f, ensure_ascii=False, indent=2, default=str)
        return path

    def _save_index_summary(self, documents: List[RetrievalDocument]):
        index_dir = self.project_root / "indexes" / "bm25"
        index_dir.mkdir(parents=True, exist_ok=True)
        summary = {
            "project_id": self.project_id,
            "updated_at": datetime.now().isoformat(),
            "document_count": len(documents),
            "source_counts": dict(Counter(doc.source_type for doc in documents)),
            "documents": [
                {
                    "doc_id": doc.doc_id,
                    "source_type": doc.source_type,
                    "path": doc.path,
                    "title": doc.title,
                    "chars": len(doc.text),
                }
                for doc in documents
            ],
        }
        with open(index_dir / "index_summary.json", "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)

    def _save_hybrid_summary(self, context_pack: Dict[str, Any]):
        hybrid_dir = self.project_root / "indexes" / "hybrid"
        hybrid_dir.mkdir(parents=True, exist_ok=True)
        retrieval = context_pack.get("hybrid_retrieval", {})
        summary = {
            "project_id": self.project_id,
            "updated_at": datetime.now().isoformat(),
            "latest_context_pack": context_pack.get("path"),
            "plan": retrieval.get("plan", {}),
            "stats": retrieval.get("stats", {}),
            "top_results": [
                {
                    "doc_id": item.get("doc_id"),
                    "source_type": item.get("source_type"),
                    "title": item.get("title"),
                    "retrieval_sources": item.get("retrieval_sources"),
                    "fusion_score": item.get("fusion_score"),
                    "rerank_score": item.get("rerank_score"),
                }
                for item in retrieval.get("results", [])[:20]
            ],
        }
        (hybrid_dir / "index_summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )

    def _read_json(self, path: Path) -> Dict[str, Any]:
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    def _read_text(self, path: Path) -> str:
        try:
            return path.read_text(encoding="utf-8")
        except Exception:
            return ""

    def _relative(self, path: Path) -> str:
        return str(path.relative_to(self.project_root)).replace("\\", "/")
