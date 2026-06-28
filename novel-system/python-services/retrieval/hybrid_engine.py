"""Local hybrid retrieval engine.

This module implements the retrieval shape from the architecture docs without
requiring an external vector database. It creates deterministic hashed vectors
from local artifacts, fuses keyword/vector/graph matches, and records rerank
scores so the system has inspectable retrieval plans and artifacts today.
"""
import hashlib
import json
import math
import os
import statistics
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from config import settings
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
    """Tiny deterministic vector index with optional model embedding vectors."""

    def __init__(
            self,
            documents: List[RetrievalDocument],
            tokenizer,
            dimensions: int = 96,
            embedding_bundle: Optional[Dict[str, Any]] = None,
            vector_backend: Optional[str] = None,
            vector_config: Optional[Dict[str, Any]] = None,
            project_root: Optional[Path] = None):
        self.documents = [doc for doc in documents if doc.text.strip()]
        self.documents_by_id = {doc.doc_id: doc for doc in self.documents}
        self.tokenizer = tokenizer
        self.project_root = project_root
        self.embedding_bundle = embedding_bundle or {}
        self.vector_config = self._normalize_vector_config(
            vector_config
            or self.embedding_bundle.get("vector_config")
            or self.embedding_bundle.get("config")
            or {}
        )
        self.embedding_metadata = self._normalize_embedding_metadata(self.embedding_bundle.get("metadata"))
        self.requested_backend = self._normalize_backend(
            vector_backend
            or self.embedding_bundle.get("vector_backend")
            or self.embedding_bundle.get("backend")
            or self.embedding_metadata.get("vector_backend")
            or self.embedding_metadata.get("backend")
        )
        provided_vectors = self._provided_vectors(self.embedding_bundle.get("document_vectors"))
        provided_dimensions = self._dimensions(provided_vectors)
        self.dimensions = provided_dimensions or dimensions
        if provided_dimensions and provided_vectors and len(provided_vectors) == len(self.documents):
            self.vectors = provided_vectors
            self.engine = "embedding_vector"
            self.vector_mode = str(self.embedding_metadata.get("vector_mode") or "model_embedding")
        else:
            self.vectors = [self._embed(doc.text) for doc in self.documents]
            self.engine = "hash_vector"
            self.vector_mode = "hash_fallback"
            if provided_vectors:
                self.embedding_metadata.setdefault("warnings", []).append(
                    "Provided embedding vector count did not match indexed documents; hash fallback was used."
                )
        self.embedding_metadata.update({
            "engine": self.engine,
            "vector_mode": self.vector_mode,
            "dimensions": self.dimensions,
            "document_vector_count": len(self.vectors),
        })
        self.vector_backend = "memory"
        self.backend_status: Dict[str, Any] = {
            "requested": self.requested_backend,
            "active": "memory",
            "status": "memory",
            "reason": "memory_backend_selected",
        }
        if self._should_use_pgvector():
            self._activate_pgvector_backend()
        elif self._should_use_lancedb():
            self._activate_lancedb_backend()
        self.embedding_metadata["vector_backend"] = self.vector_backend
        self.embedding_metadata["backend_status"] = self.backend_status

    def search(self, query: str, top_k: int = 16, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        query_vector = self._query_vector(query)
        if not query_vector:
            return []
        if self.vector_backend == "pgvector":
            try:
                return self._search_pgvector(query_vector, top_k=top_k, filters=filters)
            except Exception as exc:
                self.vector_backend = "memory"
                self.backend_status.update({
                    "active": "memory",
                    "status": "search_fallback",
                    "reason": "pgvector_search_error",
                    "error": str(exc),
                })
                self.embedding_metadata["vector_backend"] = self.vector_backend
                self.embedding_metadata["backend_status"] = self.backend_status
                self.embedding_metadata.setdefault("warnings", []).append(
                    f"pgvector search failed; memory vector fallback was used: {exc}"
                )
        if self.vector_backend == "lancedb":
            try:
                return self._search_lancedb(query_vector, top_k=top_k, filters=filters)
            except Exception as exc:
                self.vector_backend = "memory"
                self.backend_status.update({
                    "active": "memory",
                    "status": "search_fallback",
                    "reason": "lancedb_search_error",
                    "error": str(exc),
                })
                self.embedding_metadata["vector_backend"] = self.vector_backend
                self.embedding_metadata["backend_status"] = self.backend_status
                self.embedding_metadata.setdefault("warnings", []).append(
                    f"LanceDB search failed; memory vector fallback was used: {exc}"
                )
        return self._search_memory(query_vector, top_k=top_k, filters=filters)

    def _search_memory(self, query_vector: List[float], top_k: int, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        results = []
        for index, doc in enumerate(self.documents):
            if filters and not self._matches_filters(doc, filters):
                continue
            score = self._cosine(query_vector, self.vectors[index])
            results.append(self._result(doc, score, "vector"))
        results.sort(key=lambda item: item["score"], reverse=True)
        return results[:top_k]

    def persist(self, output_dir: Path, cache_status: Optional[Dict[str, Any]] = None):
        output_dir.mkdir(parents=True, exist_ok=True)
        summary = {
            "updated_at": datetime.now().isoformat(),
            "engine": self.engine,
            "vector_mode": self.vector_mode,
            "vector_backend": self.vector_backend,
            "backend_status": self.backend_status,
            "dimensions": self.dimensions,
            "document_count": len(self.documents),
            "embedding_metadata": self.embedding_metadata,
            "cache_status": cache_status or {},
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

    def summary(self) -> Dict[str, Any]:
        return {
            "engine": self.engine,
            "vector_mode": self.vector_mode,
            "vector_backend": self.vector_backend,
            "backend_status": self.backend_status,
            "dimensions": self.dimensions,
            "document_count": len(self.documents),
            "embedding_metadata": self.embedding_metadata,
        }

    def _normalize_backend(self, value: Any) -> str:
        text = str(value or "auto").strip().lower()
        if text in {"lancedb", "lance", "lance_db"}:
            return "lancedb"
        if text in {"pgvector", "postgres", "postgresql", "postgres_vector", "pg_vector"}:
            return "pgvector"
        if text in {"memory", "local", "hash", "hash_vector", "in_memory"}:
            return "memory"
        return "auto"

    def _should_use_pgvector(self) -> bool:
        if not self.documents or not self.vectors:
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "memory",
                "status": "skipped",
                "reason": "no_vectors_to_index",
            }
            return False
        if self.requested_backend == "pgvector":
            return True
        return self.requested_backend == "auto" and self.engine == "embedding_vector" and bool(self._pgvector_dsn())

    def _should_use_lancedb(self) -> bool:
        if not self.documents or not self.vectors:
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "memory",
                "status": "skipped",
                "reason": "no_vectors_to_index",
            }
            return False
        if self.requested_backend == "memory":
            return False
        if self.requested_backend == "pgvector":
            return False
        if self.requested_backend == "lancedb":
            return True
        return self.engine == "embedding_vector"

    def _activate_pgvector_backend(self):
        dsn = self._pgvector_dsn()
        if not dsn:
            self.vector_backend = "memory"
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "memory",
                "status": "fallback",
                "reason": "pgvector_dsn_missing",
            }
            self.embedding_metadata.setdefault("warnings", []).append(
                "pgvector backend requested but PGVECTOR_DSN/DATABASE_URL was not configured; memory vector fallback was used."
            )
            return
        try:
            import psycopg  # type: ignore
            project_id = self._project_id()
            rows = self._pgvector_rows()
            ann_status: Dict[str, Any] = self._pgvector_ann_status("pending")
            with psycopg.connect(dsn, autocommit=True) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
                    cursor.execute("""
                        CREATE TABLE IF NOT EXISTS retrieval_vectors (
                            id varchar(220) PRIMARY KEY,
                            project_id varchar(64) NOT NULL,
                            doc_id varchar(160) NOT NULL,
                            source_type varchar(80),
                            path varchar(700),
                            title varchar(500),
                            snippet text,
                            metadata jsonb,
                            dimensions integer,
                            embedding vector NOT NULL,
                            updated_at timestamp NOT NULL DEFAULT now(),
                            CONSTRAINT uk_retrieval_vectors_project_doc UNIQUE (project_id, doc_id)
                        )
                    """)
                    cursor.execute(
                        "CREATE INDEX IF NOT EXISTS idx_retrieval_vectors_project_id ON retrieval_vectors(project_id)"
                    )
                    cursor.execute(
                        "CREATE INDEX IF NOT EXISTS idx_retrieval_vectors_source_type ON retrieval_vectors(source_type)"
                    )
                    cursor.execute("DELETE FROM retrieval_vectors WHERE project_id = %s", (project_id,))
                    for row in rows:
                        cursor.execute("""
                            INSERT INTO retrieval_vectors (
                                id,
                                project_id,
                                doc_id,
                                source_type,
                                path,
                                title,
                                snippet,
                                metadata,
                                dimensions,
                                embedding,
                                updated_at
                            ) VALUES (
                                %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s, %s::vector, now()
                            )
                            ON CONFLICT (project_id, doc_id) DO UPDATE SET
                                source_type = EXCLUDED.source_type,
                                path = EXCLUDED.path,
                                title = EXCLUDED.title,
                                snippet = EXCLUDED.snippet,
                                metadata = EXCLUDED.metadata,
                                dimensions = EXCLUDED.dimensions,
                                embedding = EXCLUDED.embedding,
                                updated_at = now()
                        """, (
                            row["id"],
                            row["project_id"],
                            row["doc_id"],
                            row["source_type"],
                            row["path"],
                            row["title"],
                            row["snippet"],
                            row["metadata_json"],
                            row["dimensions"],
                            row["embedding_literal"],
                        ))
                    ann_status = self._ensure_pgvector_ann_index(cursor, len(rows))
            self.vector_backend = "pgvector"
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "pgvector",
                "status": "active",
                "table": "retrieval_vectors",
                "project_id": project_id,
                "document_count": len(rows),
                "dimensions": self.dimensions,
                "ann": ann_status,
            }
        except Exception as exc:
            self.vector_backend = "memory"
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "memory",
                "status": "fallback",
                "reason": "pgvector_unavailable",
                "error": str(exc),
            }
            self.embedding_metadata.setdefault("warnings", []).append(
                f"pgvector backend unavailable; memory vector fallback was used: {exc}"
            )

    def _activate_lancedb_backend(self):
        if self.project_root is None:
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "memory",
                "status": "fallback",
                "reason": "project_root_missing",
            }
            return
        try:
            import lancedb  # type: ignore
            backend_path = self.project_root / "indexes" / "vector" / "lancedb"
            backend_path.mkdir(parents=True, exist_ok=True)
            db = lancedb.connect(str(backend_path))
            table_name = "retrieval_vectors"
            rows = self._lancedb_rows()
            try:
                table = db.create_table(table_name, data=rows, mode="overwrite")
            except TypeError:
                if hasattr(db, "drop_table"):
                    try:
                        db.drop_table(table_name)
                    except Exception:
                        pass
                table = db.create_table(table_name, data=rows)
            self.lancedb_table = table
            self.vector_backend = "lancedb"
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "lancedb",
                "status": "active",
                "path": str(backend_path),
                "table": table_name,
                "document_count": len(rows),
            }
        except Exception as exc:
            self.vector_backend = "memory"
            self.backend_status = {
                "requested": self.requested_backend,
                "active": "memory",
                "status": "fallback",
                "reason": "lancedb_unavailable",
                "error": str(exc),
            }
            self.embedding_metadata.setdefault("warnings", []).append(
                f"LanceDB backend unavailable; memory vector fallback was used: {exc}"
            )

    def _lancedb_rows(self) -> List[Dict[str, Any]]:
        rows = []
        for doc, vector in zip(self.documents, self.vectors):
            rows.append({
                "vector": vector,
                "doc_id": doc.doc_id,
                "source_type": doc.source_type,
                "path": doc.path,
                "title": doc.title,
                "snippet": doc.text[:240] + ("..." if len(doc.text) > 240 else ""),
                "metadata_json": json.dumps(doc.metadata or {}, ensure_ascii=False),
            })
        return rows

    def _pgvector_rows(self) -> List[Dict[str, Any]]:
        project_id = self._project_id()
        rows = []
        for doc, vector in zip(self.documents, self.vectors):
            rows.append({
                "id": self._pgvector_row_id(project_id, doc.doc_id),
                "project_id": project_id,
                "doc_id": doc.doc_id,
                "source_type": doc.source_type,
                "path": doc.path,
                "title": doc.title,
                "snippet": doc.text[:240] + ("..." if len(doc.text) > 240 else ""),
                "metadata_json": json.dumps(doc.metadata or {}, ensure_ascii=False),
                "dimensions": len(vector),
                "embedding_literal": self._vector_literal(vector),
            })
        return rows

    def _search_lancedb(self, query_vector: List[float], top_k: int, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        limit = max(top_k * 4, top_k)
        raw_results = self.lancedb_table.search(query_vector).limit(limit).to_list()
        results: List[Dict[str, Any]] = []
        for row in raw_results:
            doc = self.documents_by_id.get(str(row.get("doc_id") or ""))
            if doc is None:
                continue
            if filters and not self._matches_filters(doc, filters):
                continue
            score = self._lancedb_score(row)
            results.append(self._result(doc, score, "vector"))
            if len(results) >= top_k:
                break
        return results

    def _lancedb_score(self, row: Dict[str, Any]) -> float:
        if isinstance(row.get("_score"), (int, float)):
            return float(row["_score"])
        if isinstance(row.get("_distance"), (int, float)):
            return 1.0 / (1.0 + max(0.0, float(row["_distance"])))
        if isinstance(row.get("score"), (int, float)):
            return float(row["score"])
        return 0.0

    def _search_pgvector(self, query_vector: List[float], top_k: int, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        dsn = self._pgvector_dsn()
        if not dsn:
            raise RuntimeError("PGVECTOR_DSN/DATABASE_URL is not configured")
        import psycopg  # type: ignore
        project_id = self._project_id()
        query_literal = self._vector_literal(query_vector)
        limit = max(top_k * 4, top_k)
        embedding_expr, query_expr = self._pgvector_distance_expressions()
        with psycopg.connect(dsn, autocommit=True) as connection:
            with connection.cursor() as cursor:
                self._apply_pgvector_query_tuning(cursor)
                cursor.execute(f"""
                    SELECT doc_id, 1 - ({embedding_expr} <=> {query_expr}) AS score
                    FROM retrieval_vectors
                    WHERE project_id = %s AND dimensions = %s
                    ORDER BY {embedding_expr} <=> {query_expr}
                    LIMIT %s
                """, (query_literal, project_id, self.dimensions, query_literal, limit))
                raw_results = cursor.fetchall()
        results: List[Dict[str, Any]] = []
        for doc_id, score in raw_results:
            doc = self.documents_by_id.get(str(doc_id))
            if doc is None:
                continue
            if filters and not self._matches_filters(doc, filters):
                continue
            results.append(self._result(doc, float(score or 0), "vector"))
            if len(results) >= top_k:
                break
        return results

    def _pgvector_dsn(self) -> str:
        return str(
            getattr(settings, "PGVECTOR_DSN", "")
            or os.getenv("PGVECTOR_DSN")
            or os.getenv("PGVECTOR_DATABASE_URL")
            or os.getenv("DATABASE_URL")
            or ""
        ).strip()

    def _project_id(self) -> str:
        return self.project_root.name if self.project_root is not None else "default"

    def _pgvector_row_id(self, project_id: str, doc_id: str) -> str:
        digest = hashlib.sha1(f"{project_id}:{doc_id}".encode("utf-8")).hexdigest()[:24]
        safe_doc_id = "".join(ch if ch.isalnum() or ch in {"_", "-"} else "_" for ch in doc_id)[:80]
        return f"{project_id}:{safe_doc_id}:{digest}"[:220]

    def _ensure_pgvector_ann_index(self, cursor, row_count: int) -> Dict[str, Any]:
        ann = self._pgvector_ann_status("configured")
        if ann["requested"] == "none":
            ann.update({"active": "none", "status": "disabled", "reason": "ann_disabled"})
            return ann
        if self.dimensions <= 0:
            ann.update({"active": "none", "status": "skipped", "reason": "invalid_dimensions"})
            return ann
        if self.dimensions > 2000:
            ann.update({
                "active": "none",
                "status": "skipped",
                "reason": "dimensions_exceed_pgvector_index_limit",
            })
            return ann

        expression = f"(embedding::vector({self.dimensions})) vector_cosine_ops"
        try:
            active = ann["requested"] if ann["requested"] in {"hnsw", "ivfflat"} else "hnsw"
            if active == "ivfflat":
                lists = self._pgvector_lists(row_count)
                index_name = self._pgvector_ann_index_name(active, {"lists": lists})
                cursor.execute(f"""
                    CREATE INDEX IF NOT EXISTS {index_name}
                    ON retrieval_vectors
                    USING ivfflat ({expression})
                    WITH (lists = {lists})
                    WHERE dimensions = {self.dimensions}
                """)
                ann.update({
                    "active": "ivfflat",
                    "status": "active",
                    "index_name": index_name,
                    "build_parameters": {"lists": lists},
                    "query_parameters": {"probes": self._pgvector_probes(lists)},
                })
            else:
                m = self._bounded_int(self._config_value("pgvector_hnsw_m", "pgvectorHnswM"), 16, 4, 64)
                ef_construction = self._bounded_int(
                    self._config_value("pgvector_hnsw_ef_construction", "pgvectorHnswEfConstruction"),
                    64,
                    8,
                    512,
                )
                index_name = self._pgvector_ann_index_name(active, {
                    "m": m,
                    "ef_construction": ef_construction,
                })
                cursor.execute(f"""
                    CREATE INDEX IF NOT EXISTS {index_name}
                    ON retrieval_vectors
                    USING hnsw ({expression})
                    WITH (m = {m}, ef_construction = {ef_construction})
                    WHERE dimensions = {self.dimensions}
                """)
                ann.update({
                    "active": "hnsw",
                    "status": "active",
                    "index_name": index_name,
                    "build_parameters": {"m": m, "ef_construction": ef_construction},
                    "query_parameters": {"ef_search": self._pgvector_hnsw_ef_search()},
                })
        except Exception as exc:
            ann.update({
                "active": "none",
                "status": "fallback_exact",
                "reason": "ann_index_error",
                "error": str(exc),
            })
            self.embedding_metadata.setdefault("warnings", []).append(
                f"pgvector ANN index could not be created; exact pgvector search remains active: {exc}"
            )
        return ann

    def _apply_pgvector_query_tuning(self, cursor):
        ann = (self.backend_status or {}).get("ann") or {}
        if ann.get("status") != "active":
            return
        if ann.get("active") == "hnsw":
            cursor.execute(f"SET hnsw.ef_search = {self._pgvector_hnsw_ef_search()}")
        elif ann.get("active") == "ivfflat":
            probes = (ann.get("query_parameters") or {}).get("probes")
            cursor.execute(f"SET ivfflat.probes = {self._bounded_int(probes, 1, 1, 10000)}")

    def _pgvector_distance_expressions(self) -> tuple:
        if 0 < self.dimensions <= 2000:
            return f"embedding::vector({self.dimensions})", f"%s::vector({self.dimensions})"
        return "embedding", "%s::vector"

    def _pgvector_ann_status(self, status: str) -> Dict[str, Any]:
        requested = self._normalize_pgvector_ann(self._config_value("pgvector_ann_index", "pgvectorAnnIndex"))
        return {
            "requested": requested,
            "active": "none",
            "status": status,
            "distance": "cosine",
            "operator_class": "vector_cosine_ops",
            "dimensions": self.dimensions,
        }

    def _normalize_pgvector_ann(self, value: Any) -> str:
        text = str(value or "auto").strip().lower()
        if text in {"off", "none", "disabled", "disable", "false", "0"}:
            return "none"
        if text in {"ivfflat", "ivf", "ivf_flat"}:
            return "ivfflat"
        if text in {"hnsw"}:
            return "hnsw"
        return "auto"

    def _pgvector_ann_index_name(self, active: str, parameters: Optional[Dict[str, Any]] = None) -> str:
        params = parameters or {}
        if active == "ivfflat":
            suffix = f"l{self._bounded_int(params.get('lists'), 0, 0, 100000)}"
            return f"idx_rv_ivf_d{self.dimensions}_{suffix}_cos"
        suffix = "_".join([
            f"m{self._bounded_int(params.get('m'), 0, 0, 100000)}",
            f"efc{self._bounded_int(params.get('ef_construction'), 0, 0, 100000)}",
        ])
        return f"idx_rv_hnsw_d{self.dimensions}_{suffix}_cos"

    def _pgvector_lists(self, row_count: int) -> int:
        configured = self._config_value("pgvector_lists", "pgvectorLists")
        if configured is not None:
            return self._bounded_int(configured, 1, 1, 100000)
        if row_count <= 0:
            return 1
        if row_count <= 1_000_000:
            return max(1, row_count // 1000)
        return max(1, int(math.sqrt(row_count)))

    def _pgvector_probes(self, lists: int) -> int:
        configured = self._config_value("pgvector_probes", "pgvectorProbes")
        if configured is not None:
            return self._bounded_int(configured, 1, 1, max(1, lists))
        return max(1, int(math.sqrt(max(1, lists))))

    def _pgvector_hnsw_ef_search(self) -> int:
        return self._bounded_int(
            self._config_value("pgvector_hnsw_ef_search", "pgvectorHnswEfSearch"),
            40,
            1,
            10000,
        )

    def _normalize_vector_config(self, value: Any) -> Dict[str, Any]:
        return dict(value) if isinstance(value, dict) else {}

    def _config_value(self, *keys: str) -> Any:
        for key in keys:
            if key in self.vector_config:
                return self.vector_config.get(key)
        return None

    def _bounded_int(self, value: Any, default: int, minimum: int, maximum: int) -> int:
        try:
            number = int(value)
        except (TypeError, ValueError):
            number = default
        return max(minimum, min(maximum, number))

    def _vector_literal(self, vector: List[float]) -> str:
        return "[" + ",".join(f"{float(value):.8f}" for value in vector) + "]"

    def _query_vector(self, query: str) -> List[float]:
        query_vectors = self._provided_vectors(self.embedding_bundle.get("query_vectors"))
        if query_vectors:
            query_vector = query_vectors[0]
            if len(query_vector) == self.dimensions:
                return query_vector
            self.embedding_metadata.setdefault("warnings", []).append(
                "Provided query embedding dimension did not match index dimensions; hash query vector was used."
            )
        return self._embed(query)

    def _provided_vectors(self, value: Any) -> List[List[float]]:
        if not isinstance(value, list):
            return []
        vectors: List[List[float]] = []
        for item in value:
            if not isinstance(item, list):
                return []
            try:
                vector = [float(number) for number in item]
            except (TypeError, ValueError):
                return []
            vectors.append(self._normalize_vector(vector))
        return vectors

    def _normalize_vector(self, vector: List[float]) -> List[float]:
        norm = math.sqrt(sum(value * value for value in vector))
        if norm <= 0:
            return vector
        return [value / norm for value in vector]

    def _dimensions(self, vectors: List[List[float]]) -> Optional[int]:
        if not vectors:
            return None
        dimensions = len(vectors[0])
        if dimensions <= 0:
            return None
        if any(len(vector) != dimensions for vector in vectors):
            self.embedding_metadata.setdefault("warnings", []).append(
                "Embedding vectors had inconsistent dimensions; hash fallback was used."
            )
            return None
        return dimensions

    def _normalize_embedding_metadata(self, metadata: Any) -> Dict[str, Any]:
        if isinstance(metadata, dict):
            normalized = dict(metadata)
        else:
            normalized = {}
        warnings = normalized.get("warnings")
        if warnings is None:
            normalized["warnings"] = []
        elif not isinstance(warnings, list):
            normalized["warnings"] = [str(warnings)]
        return normalized

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
    def __init__(
            self,
            project_root: Path,
            documents: List[RetrievalDocument],
            graph_context: Dict[str, Any],
            embedding_bundle: Optional[Dict[str, Any]] = None,
            vector_backend: Optional[str] = None,
            vector_config: Optional[Dict[str, Any]] = None):
        self.project_root = project_root
        self.documents = documents
        self.keyword = KeywordRetriever(documents)
        self.vector = HashVectorIndex(
            documents,
            self.keyword._tokenize,
            embedding_bundle=embedding_bundle,
            vector_backend=vector_backend,
            vector_config=vector_config,
            project_root=project_root,
        )
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
        quality_evaluation = self._quality_evaluation(query, runs, fused, reranked, top_k)

        return {
            "plan": asdict(plan),
            "runs": runs,
            "fused_results": fused,
            "reranked_results": reranked,
            "results": reranked,
            "quality_evaluation": quality_evaluation,
            "stats": {
                "keyword_count": len(runs.get("keyword", [])),
                "vector_count": len(runs.get("vector", [])),
                "graph_count": len(runs.get("graph", [])),
                "fused_count": len(fused),
                "returned_count": len(reranked),
                "quality_score": quality_evaluation.get("score"),
                "quality_status": quality_evaluation.get("status"),
                "vector_mode": self.vector.vector_mode,
                "vector_engine": self.vector.engine,
                "vector_backend": self.vector.vector_backend,
            },
            "vector_index": self.vector.summary(),
        }

    def evaluate_benchmark(
            self,
            benchmark_queries: List[Dict[str, Any]],
            plan: RetrievalPlan,
            top_k: int = 10) -> Dict[str, Any]:
        """Evaluate retrieval against a deterministic local benchmark set."""
        cases = [case for case in benchmark_queries if str(case.get("query") or "").strip()]
        evaluated_cases = []
        for index, case in enumerate(cases, start=1):
            case_top_k = self._bounded_int(case.get("top_k"), top_k, 1, 50)
            retrieval = self.retrieve(str(case.get("query")), plan, top_k=case_top_k)
            results = retrieval.get("results", [])
            expected = self._expected_matchers(case)
            matched_results = [
                self._benchmark_match({**result, "rank": rank}, expected)
                for rank, result in enumerate(results, start=1)
            ]
            matched_results = [match for match in matched_results if match.get("matched")]
            first_rank = min((match["rank"] for match in matched_results), default=None)
            expected_count = max(1, expected.get("expected_count", 1))
            hit_count = len({match["identity"] for match in matched_results})
            recall_at_k = min(1.0, hit_count / expected_count)
            reciprocal_rank = round(1 / first_rank, 6) if first_rank else 0
            min_quality_score = self._bounded_int(case.get("min_quality_score"), 50, 0, 100)
            quality_score = int(retrieval.get("quality_evaluation", {}).get("score") or 0)
            passed = bool(first_rank) and quality_score >= min_quality_score

            evaluated_cases.append({
                "id": case.get("id") or f"benchmark_{index}",
                "query": case.get("query"),
                "description": case.get("description", ""),
                "top_k": case_top_k,
                "passed": passed,
                "hit": bool(first_rank),
                "first_match_rank": first_rank,
                "reciprocal_rank": reciprocal_rank,
                "hit_count": hit_count,
                "expected_count": expected_count,
                "recall_at_k": round(recall_at_k, 6),
                "quality_score": quality_score,
                "quality_status": retrieval.get("quality_evaluation", {}).get("status"),
                "min_quality_score": min_quality_score,
                "expected": expected.get("summary", {}),
                "matched_results": matched_results[:case_top_k],
                "top_results": [
                    {
                        "rank": rank,
                        "doc_id": result.get("doc_id"),
                        "source_type": result.get("source_type"),
                        "path": result.get("path"),
                        "title": result.get("title"),
                        "rerank_score": result.get("rerank_score"),
                        "retrieval_sources": result.get("retrieval_sources"),
                    }
                    for rank, result in enumerate(results, start=1)
                ],
                "quality_evaluation": retrieval.get("quality_evaluation", {}),
            })

        case_count = len(evaluated_cases)
        passed_count = sum(1 for case in evaluated_cases if case["passed"])
        hit_count = sum(1 for case in evaluated_cases if case["hit"])
        average_recall = statistics.mean([case["recall_at_k"] for case in evaluated_cases]) if evaluated_cases else 0
        mean_reciprocal_rank = statistics.mean([case["reciprocal_rank"] for case in evaluated_cases]) if evaluated_cases else 0
        average_quality_score = statistics.mean([case["quality_score"] for case in evaluated_cases]) if evaluated_cases else 0
        pass_rate = passed_count / case_count if case_count else 0
        hit_rate = hit_count / case_count if case_count else 0
        status = "passed" if case_count and pass_rate >= 0.8 else ("needs_review" if case_count and hit_rate >= 0.5 else "failed")

        warnings = []
        recommendations = []
        if not case_count:
            warnings.append("No retrieval benchmark queries were provided.")
            recommendations.append("Add indexes/retrieval_benchmark.json or pass benchmark_queries to retrieval_index.")
        for case in evaluated_cases:
            if not case["hit"]:
                warnings.append(f"{case['id']} did not match expected retrieval evidence.")
                recommendations.append("Tune query wording, expected evidence, or rebuild indexes with missing sources.")
            elif not case["passed"]:
                warnings.append(f"{case['id']} matched evidence but failed quality threshold.")
                recommendations.append("Improve source diversity or quality score for benchmark queries.")

        return {
            "evaluated_at": datetime.now().isoformat(),
            "status": status,
            "case_count": case_count,
            "passed_count": passed_count,
            "hit_count": hit_count,
            "pass_rate": round(pass_rate, 6),
            "hit_rate": round(hit_rate, 6),
            "average_recall_at_k": round(average_recall, 6),
            "mean_reciprocal_rank": round(mean_reciprocal_rank, 6),
            "average_quality_score": round(average_quality_score, 2),
            "warnings": warnings,
            "recommendations": sorted(set(recommendations)),
            "vector_index": self.vector.summary(),
            "cases": evaluated_cases,
        }

    def persist_indexes(self, cache_status: Optional[Dict[str, Any]] = None):
        self.vector.persist(self.project_root / "indexes" / "vector", cache_status=cache_status)

    def _expected_matchers(self, case: Dict[str, Any]) -> Dict[str, Any]:
        doc_ids = self._string_set(case.get("expected_doc_ids") or case.get("expectedDocIds"))
        paths = self._string_set(case.get("expected_paths") or case.get("expectedPaths"))
        source_types = self._string_set(case.get("expected_source_types") or case.get("expectedSourceTypes"))
        contains = self._string_set(case.get("expected_text") or case.get("expectedText") or case.get("expected_contains"))
        expected_count = int(case.get("expected_count") or case.get("expectedCount") or max(
            1,
            len(doc_ids) + len(paths) + len(source_types) + len(contains)
        ))
        return {
            "doc_ids": doc_ids,
            "paths": paths,
            "source_types": source_types,
            "contains": {item.lower() for item in contains},
            "expected_count": expected_count,
            "summary": {
                "docIds": sorted(doc_ids),
                "paths": sorted(paths),
                "sourceTypes": sorted(source_types),
                "contains": sorted(contains),
                "expectedCount": expected_count,
            },
        }

    def _benchmark_match(self, result: Dict[str, Any], expected: Dict[str, Any]) -> Dict[str, Any]:
        text = " ".join([
            str(result.get("doc_id", "")),
            str(result.get("source_type", "")),
            str(result.get("path", "")),
            str(result.get("title", "")),
            str(result.get("snippet", "")),
        ]).lower()
        matched_by = []
        if result.get("doc_id") in expected["doc_ids"]:
            matched_by.append("doc_id")
        if result.get("path") in expected["paths"]:
            matched_by.append("path")
        if result.get("source_type") in expected["source_types"]:
            matched_by.append("source_type")
        if any(item and item in text for item in expected["contains"]):
            matched_by.append("text")
        rank = int(result.get("rank") or 0)
        return {
            "matched": bool(matched_by),
            "matched_by": matched_by,
            "identity": result.get("doc_id") or result.get("path") or result.get("title"),
            "rank": rank,
            "doc_id": result.get("doc_id"),
            "source_type": result.get("source_type"),
            "path": result.get("path"),
            "title": result.get("title"),
            "rerank_score": result.get("rerank_score"),
        }

    def _string_set(self, value: Any) -> set:
        if value is None:
            return set()
        if isinstance(value, str):
            return {value} if value.strip() else set()
        if isinstance(value, list):
            return {str(item) for item in value if str(item).strip()}
        return {str(value)}

    def _bounded_int(self, value: Any, default: int, minimum: int, maximum: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            parsed = default
        return max(minimum, min(maximum, parsed))

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

    def _quality_evaluation(
            self,
            query: str,
            runs: Dict[str, List[Dict[str, Any]]],
            fused: List[Dict[str, Any]],
            results: List[Dict[str, Any]],
            top_k: int) -> Dict[str, Any]:
        query_tokens = set(self.keyword._tokenize(query))
        matched_tokens = set()
        source_types = Counter()
        retrieval_sources = Counter()
        paths = Counter()
        doc_ids = Counter()
        rerank_scores = []

        for item in results:
            source_types[item.get("source_type") or "unknown"] += 1
            for source in item.get("retrieval_sources") or [item.get("retrieval_source") or "unknown"]:
                retrieval_sources[source] += 1
            if item.get("path"):
                paths[item["path"]] += 1
            if item.get("doc_id"):
                doc_ids[item["doc_id"]] += 1
            if isinstance(item.get("rerank_score"), (int, float)):
                rerank_scores.append(float(item["rerank_score"]))

            text = " ".join([
                str(item.get("title", "")),
                str(item.get("snippet", "")),
                json.dumps(item.get("metadata", {}), ensure_ascii=False),
            ])
            matched_tokens.update(query_tokens & set(self.keyword._tokenize(text)))

        returned_count = len(results)
        returned_ratio = returned_count / max(1, top_k)
        token_coverage = len(matched_tokens) / max(1, len(query_tokens))
        source_diversity = len(source_types) / max(1, min(3, returned_count or 1))
        duplicate_path_count = sum(count - 1 for count in paths.values() if count > 1)
        duplicate_doc_count = sum(count - 1 for count in doc_ids.values() if count > 1)
        duplicate_penalty = min(0.25, (duplicate_path_count + duplicate_doc_count) * 0.05)
        run_coverage = sum(1 for key in ("keyword", "vector", "graph") if runs.get(key)) / 3

        score = (
            min(1.0, returned_ratio) * 30
            + min(1.0, token_coverage) * 30
            + min(1.0, source_diversity) * 20
            + run_coverage * 20
            - duplicate_penalty * 100
        )
        score = max(0, min(100, round(score)))

        warnings = []
        recommendations = []
        if returned_count < top_k:
            warnings.append(f"仅返回 {returned_count}/{top_k} 条结果")
            recommendations.append("扩大索引文档范围或降低过滤条件")
        if token_coverage < 0.35 and query_tokens:
            warnings.append("查询词覆盖率偏低")
            recommendations.append("补充大纲关键词、人物名或场景目标后重建检索")
        if len(source_types) < 2 and returned_count > 1:
            warnings.append("结果来源类型较单一")
            recommendations.append("开启关键词、向量、图谱混合检索并增加样本/记忆文档")
        if duplicate_path_count or duplicate_doc_count:
            warnings.append("存在重复来源结果")
            recommendations.append("检查样本分块和 Markdown 产物是否重复写入")
        if not any(runs.values()):
            warnings.append("所有检索通道均未命中")
            recommendations.append("先导入样本、生成记忆/图谱，再重建索引")

        if score >= 75:
            status = "good"
        elif score >= 50:
            status = "needs_review"
        else:
            status = "poor"

        metrics = {
            "requested_top_k": top_k,
            "returned_count": returned_count,
            "returned_ratio": round(returned_ratio, 4),
            "query_token_count": len(query_tokens),
            "matched_query_tokens": len(matched_tokens),
            "query_token_coverage": round(token_coverage, 4),
            "source_type_count": len(source_types),
            "source_types": sorted(source_types.keys()),
            "source_type_distribution": dict(source_types),
            "retrieval_source_distribution": dict(retrieval_sources),
            "duplicate_path_count": duplicate_path_count,
            "duplicate_doc_id_count": duplicate_doc_count,
            "run_counts": {key: len(value) for key, value in runs.items()},
            "fused_count": len(fused),
            "rerank_score_min": round(min(rerank_scores), 6) if rerank_scores else None,
            "rerank_score_max": round(max(rerank_scores), 6) if rerank_scores else None,
            "rerank_score_avg": round(statistics.mean(rerank_scores), 6) if rerank_scores else None,
            "rerank_score_spread": round(max(rerank_scores) - min(rerank_scores), 6) if len(rerank_scores) > 1 else 0,
        }

        return {
            "evaluated_at": datetime.now().isoformat(),
            "score": score,
            "status": status,
            "warnings": warnings,
            "recommendations": sorted(set(recommendations)),
            "metrics": metrics,
        }
