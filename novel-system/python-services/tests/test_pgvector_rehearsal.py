import json
import subprocess
import sys
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "pgvector_rehearsal.py"


def test_pgvector_rehearsal_reports_missing_required_dsn(tmp_path, monkeypatch):
    monkeypatch.delenv("PGVECTOR_DSN", raising=False)
    monkeypatch.delenv("PGVECTOR_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    output = tmp_path / "pgvector_rehearsal_report.json"

    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--require-dsn",
            "--output",
            str(output),
        ],
        cwd=SCRIPT.parents[1],
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode == 2
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["status"] == "skipped"
    assert report["reason"] == "pgvector_dsn_missing"
    assert "pgvector_dsn_missing" in result.stdout


def test_pgvector_rehearsal_vectors_are_deterministic():
    import importlib.util

    spec = importlib.util.spec_from_file_location("pgvector_rehearsal", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    document_vectors = module.deterministic_vectors(module.DOCUMENTS)
    assert len(document_vectors) == len(module.DOCUMENTS)
    assert {len(vector) for vector in document_vectors} == {8}
    assert module.query_vector("jade token snow gate")[0] > module.query_vector("chapter hook")[0]
    assert module.query_vector("unanswered clue next chapter hook")[2] > 0.9


def test_pgvector_rehearsal_splits_migration_sql():
    import importlib.util

    spec = importlib.util.spec_from_file_location("pgvector_rehearsal", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    statements = module.split_sql_statements(module.PGVECTOR_MIGRATION.read_text(encoding="utf-8"))

    assert statements[0].lower().startswith("create extension")
    assert any("create table if not exists retrieval_vectors" in statement.lower() for statement in statements)
    assert all(not statement.rstrip().endswith(";") for statement in statements)
