import sys
from pathlib import Path


SCRIPTS_ROOT = Path(__file__).resolve().parents[2] / "scripts"
if str(SCRIPTS_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_ROOT))

from smoke_mvp_pipeline import run_smoke


async def test_smoke_mvp_pipeline_creates_required_artifacts(tmp_path):
    result = await run_smoke(tmp_path, project_id="proj_smoke_test")

    assert result["status"] == "passed"
    assert result["sample_count"] == 2
    assert result["skill_count"] == 3
    assert all(sample["coverage_status"] == "passed" for sample in result["samples"])
    assert all(sample["analysis_coverage_ratio"] == 1.0 for sample in result["samples"])

    project_root = tmp_path / "projects" / "proj_smoke_test"
    for relative_path in result["artifact_checks"]:
        assert (project_root / relative_path).exists()

    enabled_yaml = (project_root / "skills" / "enabled.yaml").read_text(encoding="utf-8")
    assert "writing_skill" in enabled_yaml
    assert "outline_skill" in enabled_yaml
    assert "review_skill" in enabled_yaml
