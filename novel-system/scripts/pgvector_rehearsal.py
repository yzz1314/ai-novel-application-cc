"""Run a real pgvector retrieval rehearsal and write an audit report.

The rehearsal is intentionally small and deterministic: it verifies that a
PostgreSQL instance with the pgvector extension can accept the retrieval schema,
store embeddings through the production HashVectorIndex path, build ANN indexes,
and answer similarity queries. It is useful both for local Docker rehearsals and
CI services backed by pgvector/pgvector:pg15.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import shutil
import statistics
import sys
import tempfile
import types
import time
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


PYTHON_SERVICES_ROOT = Path(__file__).resolve().parents[1] / "python-services"
PGVECTOR_MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "java-services"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V14__add_retrieval_vectors_pgvector.sql"
)
if str(PYTHON_SERVICES_ROOT) not in sys.path:
    sys.path.insert(0, str(PYTHON_SERVICES_ROOT))

if "litellm" not in sys.modules:
    try:
        has_litellm = importlib.util.find_spec("litellm") is not None
    except ValueError:
        has_litellm = False
    if not has_litellm:
        sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from config import settings
from retrieval.context_builder import KeywordRetriever, RetrievalDocument
from retrieval.hybrid_engine import HashVectorIndex


DEFAULT_DSN = "postgresql://novel_user:password@localhost:5432/novel_system"

DOCUMENTS = [
    RetrievalDocument(
        doc_id="sample_chunk:snow_gate",
        source_type="sample_chunk",
        path="samples/chunks/sample_a/chunk_0001.json",
        title="Snow Gate Token",
        text=(
            "Rowan holds the jade token at the trial gate while snow covers the "
            "steps. The sealed hall answers with a quiet click."
        ),
        metadata={"chapter": 1, "tags": ["jade_token", "trial_gate"]},
    ),
    RetrievalDocument(
        doc_id="skill:chapter_hook",
        source_type="skill",
        path="skills/local/writing_skill.md",
        title="Chapter Hook Technique",
        text=(
            "Use a concrete object, an unanswered clue, and a delayed reveal to "
            "pull the reader into the next chapter."
        ),
        metadata={"skill_type": "writing"},
    ),
    RetrievalDocument(
        doc_id="memory:lantern_case",
        source_type="memory",
        path="memory/foreshadowing.md",
        title="Lantern Case Memory",
        text=(
            "The lantern office case links the jade token, the snow witness, and "
            "the forbidden hall into one unresolved thread."
        ),
        metadata={"memory_type": "foreshadowing"},
    ),
    RetrievalDocument(
        doc_id="outline:chapter_2",
        source_type="outline",
        path="novel/outline/book_1_outline.json",
        title="Chapter 2 Hidden Thread",
        text=(
            "Chapter two should connect the missing senior disciple, the red note, "
            "and the underground room without resolving the main secret."
        ),
        metadata={"volume": 1, "chapter": 2},
    ),
]

QUERIES = [
    {"query": "jade token snow gate", "expected_doc_id": "sample_chunk:snow_gate"},
    {"query": "unanswered clue next chapter hook", "expected_doc_id": "skill:chapter_hook"},
    {"query": "lantern case snow witness", "expected_doc_id": "memory:lantern_case"},
]


@contextmanager
def temporary_settings(workspace: Path, dsn: str):
    original_base_path = settings.PROJECT_BASE_PATH
    original_pgvector_dsn = settings.PGVECTOR_DSN
    settings.PROJECT_BASE_PATH = str(workspace)
    settings.PGVECTOR_DSN = dsn
    try:
        yield
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
        settings.PGVECTOR_DSN = original_pgvector_dsn


def deterministic_vectors(documents: List[RetrievalDocument]) -> List[List[float]]:
    seeds = {
        "sample_chunk:snow_gate": [0.92, 0.34, 0.08, 0.05, 0.02, 0.01, 0.01, 0.00],
        "skill:chapter_hook": [0.06, 0.12, 0.89, 0.37, 0.04, 0.02, 0.01, 0.00],
        "memory:lantern_case": [0.72, 0.38, 0.10, 0.08, 0.51, 0.21, 0.02, 0.01],
        "outline:chapter_2": [0.30, 0.14, 0.25, 0.71, 0.11, 0.54, 0.02, 0.01],
    }
    return [seeds[document.doc_id] for document in documents]


def query_vector(query: str) -> List[float]:
    lowered = query.lower()
    if "chapter hook" in lowered or "unanswered clue" in lowered:
        return [0.05, 0.10, 0.94, 0.31, 0.04, 0.03, 0.01, 0.00]
    if "lantern" in lowered or "witness" in lowered:
        return [0.69, 0.40, 0.09, 0.06, 0.56, 0.17, 0.02, 0.01]
    return [0.95, 0.30, 0.07, 0.04, 0.02, 0.01, 0.01, 0.00]


def embedding_bundle(query: str, vector_config: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "document_vectors": deterministic_vectors(DOCUMENTS),
        "query_vectors": [query_vector(query)],
        "metadata": {
            "vector_mode": "model_embedding",
            "source": "pgvector_rehearsal",
        },
        "vector_backend": "pgvector",
        "vector_config": vector_config,
    }


def pgvector_dsn(explicit: Optional[str]) -> str:
    return str(
        explicit
        or os.getenv("PGVECTOR_DSN")
        or os.getenv("PGVECTOR_DATABASE_URL")
        or os.getenv("DATABASE_URL")
        or DEFAULT_DSN
    ).strip()


def ensure_database_ready(dsn: str) -> Dict[str, Any]:
    import psycopg  # type: ignore

    started = time.perf_counter()
    migration_statements = split_sql_statements(PGVECTOR_MIGRATION.read_text(encoding="utf-8"))
    with psycopg.connect(dsn, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
            for statement in migration_statements:
                cursor.execute(statement)
            cursor.execute("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
            extension_version = cursor.fetchone()[0]
            cursor.execute("SELECT current_database(), current_schema()")
            database, schema = cursor.fetchone()
            cursor.execute("SELECT version()")
            postgres_version = cursor.fetchone()[0]
            cursor.execute("SELECT to_regclass('retrieval_vectors') IS NOT NULL")
            retrieval_vectors_exists = bool(cursor.fetchone()[0])
    return {
        "status": "passed",
        "duration_ms": elapsed_ms(started),
        "extension": "vector",
        "extension_version": extension_version,
        "database": database,
        "schema": schema,
        "postgres_version": postgres_version,
        "migration_file": str(PGVECTOR_MIGRATION),
        "migration_statement_count": len(migration_statements),
        "retrieval_vectors_exists": retrieval_vectors_exists,
    }


def inspect_database(dsn: str, project_id: str) -> Dict[str, Any]:
    import psycopg  # type: ignore

    with psycopg.connect(dsn, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT count(*) FROM retrieval_vectors WHERE project_id = %s",
                (project_id,),
            )
            row_count = int(cursor.fetchone()[0] or 0)
            cursor.execute(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'retrieval_vectors'
                ORDER BY indexname
                """
            )
            index_names = [row[0] for row in cursor.fetchall()]
    return {"row_count": row_count, "index_names": index_names}


def build_index(
    workspace: Path,
    project_id: str,
    dsn: str,
    ann_name: str,
    vector_config: Dict[str, Any],
) -> Dict[str, Any]:
    project_root = workspace / "projects" / project_id
    query_reports: List[Dict[str, Any]] = []
    latest_summary: Dict[str, Any] = {}
    durations: List[float] = []
    tokenizer = KeywordRetriever([])._tokenize

    for case in QUERIES:
        started = time.perf_counter()
        index = HashVectorIndex(
            DOCUMENTS,
            tokenizer,
            embedding_bundle=embedding_bundle(case["query"], vector_config),
            vector_backend="pgvector",
            vector_config=vector_config,
            project_root=project_root,
        )
        results = index.search(case["query"], top_k=3)
        duration = elapsed_ms(started)
        durations.append(duration)
        latest_summary = index.summary()
        top_doc_id = results[0]["doc_id"] if results else None
        query_reports.append({
            "query": case["query"],
            "expected_doc_id": case["expected_doc_id"],
            "top_doc_id": top_doc_id,
            "hit": top_doc_id == case["expected_doc_id"],
            "duration_ms": duration,
            "top_results": results,
        })

    database = inspect_database(dsn, project_id)
    hits = sum(1 for item in query_reports if item["hit"])
    return {
        "ann": ann_name,
        "status": "passed" if hits == len(query_reports) else "failed",
        "duration_ms": round(sum(durations), 3),
        "mean_query_ms": round(statistics.mean(durations), 3) if durations else 0,
        "hit_count": hits,
        "case_count": len(query_reports),
        "hit_rate": round(hits / len(query_reports), 6) if query_reports else 0,
        "vector_summary": latest_summary,
        "database": database,
        "queries": query_reports,
    }


def rehearsal(workspace: Path, project_id: str, dsn: str) -> Dict[str, Any]:
    started = time.perf_counter()
    project_root = workspace / "projects" / project_id
    (project_root / "indexes").mkdir(parents=True, exist_ok=True)

    with temporary_settings(workspace, dsn):
        migration = ensure_database_ready(dsn)
        runs = [
            build_index(
                workspace,
                project_id,
                dsn,
                "hnsw",
                {
                    "pgvector_ann_index": "hnsw",
                    "pgvector_hnsw_m": 8,
                    "pgvector_hnsw_ef_construction": 32,
                    "pgvector_hnsw_ef_search": 20,
                },
            ),
            build_index(
                workspace,
                project_id,
                dsn,
                "ivfflat",
                {
                    "pgvector_ann_index": "ivfflat",
                    "pgvector_lists": 1,
                    "pgvector_probes": 1,
                },
            ),
        ]

    status = "passed" if migration["status"] == "passed" and all(run["status"] == "passed" for run in runs) else "failed"
    return {
        "status": status,
        "generated_at": datetime.now().isoformat(),
        "project_id": project_id,
        "duration_ms": elapsed_ms(started),
        "migration": migration,
        "ann_runs": runs,
        "summary": {
            "ann_modes": [run["ann"] for run in runs],
            "case_count": len(QUERIES),
            "all_hits": all(run["hit_count"] == len(QUERIES) for run in runs),
            "row_count": runs[-1]["database"]["row_count"] if runs else 0,
            "index_names": runs[-1]["database"]["index_names"] if runs else [],
        },
    }


def write_report(report: Dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


def split_sql_statements(sql: str) -> List[str]:
    statements: List[str] = []
    current: List[str] = []
    for line in sql.splitlines():
        stripped = line.strip()
        if stripped.startswith("--") or not stripped:
            continue
        current.append(line)
        if stripped.endswith(";"):
            statement = "\n".join(current).strip().rstrip(";").strip()
            if statement:
                statements.append(statement)
            current = []
    tail = "\n".join(current).strip()
    if tail:
        statements.append(tail)
    return statements


def elapsed_ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 3)


def parse_args(argv: Optional[Iterable[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run pgvector migration and ANN retrieval rehearsal.")
    parser.add_argument("--dsn", default=None, help="PostgreSQL DSN. Defaults to PGVECTOR_DSN or local CI DSN.")
    parser.add_argument("--workspace", default=None, help="Workspace root for generated rehearsal artifacts.")
    parser.add_argument("--project-id", default="pgvector_rehearsal_project")
    parser.add_argument("--output", default=None, help="Report path. Defaults under the rehearsal project indexes dir.")
    parser.add_argument("--keep-workspace", action="store_true", help="Keep temporary workspace after completion.")
    parser.add_argument("--require-dsn", action="store_true", help="Fail instead of skipping when no DSN is available.")
    return parser.parse_args(argv)


def main(argv: Optional[Iterable[str]] = None) -> int:
    args = parse_args(argv)
    explicit_dsn = args.dsn or os.getenv("PGVECTOR_DSN") or os.getenv("PGVECTOR_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not explicit_dsn and args.require_dsn:
        report = {
            "status": "skipped",
            "reason": "pgvector_dsn_missing",
            "generated_at": datetime.now().isoformat(),
        }
        output = Path(args.output or "pgvector_rehearsal_report.json")
        write_report(report, output)
        print(json.dumps(report, ensure_ascii=False))
        return 2

    dsn = pgvector_dsn(args.dsn)
    temp_workspace: Optional[Path] = None
    if args.workspace:
        workspace = Path(args.workspace).resolve()
        workspace.mkdir(parents=True, exist_ok=True)
    else:
        temp_workspace = Path(tempfile.mkdtemp(prefix="novel-pgvector-rehearsal-")).resolve()
        workspace = temp_workspace

    if args.output:
        output = Path(args.output).resolve()
    elif args.workspace:
        output = workspace / "projects" / args.project_id / "indexes" / "pgvector_rehearsal_report.json"
    else:
        output = Path.cwd() / "pgvector_rehearsal_report.json"

    try:
        report = rehearsal(workspace, args.project_id, dsn)
        write_report(report, output)
        print(json.dumps({"status": report["status"], "report": str(output)}, ensure_ascii=False))
        return 0 if report["status"] == "passed" else 1
    except Exception as exc:
        report = {
            "status": "failed",
            "generated_at": datetime.now().isoformat(),
            "project_id": args.project_id,
            "error": str(exc),
            "error_type": exc.__class__.__name__,
        }
        write_report(report, output)
        print(json.dumps({"status": "failed", "report": str(output), "error": str(exc)}, ensure_ascii=False))
        return 1
    finally:
        if temp_workspace is not None and not args.keep_workspace:
            shutil.rmtree(temp_workspace, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
