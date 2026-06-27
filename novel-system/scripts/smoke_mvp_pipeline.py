"""Run the offline MVP pipeline smoke check.

This script exercises the sample analysis path with deterministic mock LLM
responses:

project workspace -> sample import -> full text analysis -> coverage check ->
book summary -> cross-book synthesis -> skill generation.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import shutil
import sys
import tempfile
import types
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


PYTHON_SERVICES_ROOT = Path(__file__).resolve().parents[1] / "python-services"
if str(PYTHON_SERVICES_ROOT) not in sys.path:
    sys.path.insert(0, str(PYTHON_SERVICES_ROOT))

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.book_summary_agent import BookSummaryAgent
from agents.coverage_check_agent import CoverageCheckAgent
from agents.cross_book_synthesis_agent import CrossBookSynthesisAgent
from agents.full_text_analysis_agent import FullTextAnalysisAgent
from agents.sample_import_agent import SampleImportAgent
from agents.skill_generator_agent import SkillGeneratorAgent
from config import settings
from schemas.agent_request import AgentRequest


SAMPLE_TEXTS = {
    "sample_a": """Frost Gate Token

Chapter 1 The Trial Gate
Snow gathered on the stone steps before the trial gate. Rowan held the warm
jade token in his palm and heard old case files turning behind the sealed door.
The gatekeeper said no one had entered that night, yet a lamp burned on the
third floor of the forbidden hall. Rowan pressed the token into the door seam.
A quiet click answered him from the dark.

Chapter 2 The Hidden Thread
Mira caught up as Rowan found a red note on the case file. The missing senior
disciple had not betrayed the sect; he had carried something that could not be
seen in daylight. The snow stopped outside the window. Footsteps rose from the
lower hall, and the cracked token pointed toward the underground room.
""",
    "sample_b": """Lantern Archive

Chapter 1 The Unnamed Testimony
The bronze bell in the Lantern Office rang at midnight. Lio woke to find an
unsigned testimony on his desk. It said the old case was still open, and the
real key waited in the hand of someone who had returned from the snow. Before
he finished reading, a blade scraped against the courtyard gate.

Chapter 2 Snow Alley Witness
The witness said only one thing: do not trust the lamp. Then he fell at the end
of the snow alley. Lio followed the trail and found a fresh gate mark carved
into the wall. Every clue pointed to the same night, the same jade token, and a
city gate that should have remained closed.
""",
}


def _request(
    task_id: str,
    project_id: str,
    task_type: str,
    input_refs: Optional[Dict[str, Any]] = None,
    parameters: Optional[Dict[str, Any]] = None,
) -> AgentRequest:
    return AgentRequest(
        task_id=task_id,
        project_id=project_id,
        task_type=task_type,
        input_refs=input_refs or {},
        parameters=parameters or {},
    )


@contextmanager
def _temporary_settings(workspace: Path):
    original_base_path = settings.PROJECT_BASE_PATH
    original_skills_path = settings.SKILLS_PATH
    original_mock_llm = settings.MOCK_LLM
    settings.PROJECT_BASE_PATH = str(workspace)
    settings.SKILLS_PATH = str(workspace / "skills")
    settings.MOCK_LLM = True
    try:
        yield
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
        settings.SKILLS_PATH = original_skills_path
        settings.MOCK_LLM = original_mock_llm


def _write_raw_samples(workspace: Path, project_id: str) -> Dict[str, str]:
    raw_dir = workspace / "projects" / project_id / "samples" / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    paths: Dict[str, str] = {}
    for sample_id, text in SAMPLE_TEXTS.items():
        path = raw_dir / f"{sample_id}.txt"
        path.write_text(text, encoding="utf-8")
        paths[sample_id] = str(path)
    return paths


def _read_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _assert_response_success(response, step: str) -> None:
    if response.status != "success":
        details = {
            "status": response.status,
            "errors": response.errors,
            "warnings": response.warnings,
            "structured_output": response.structured_output,
        }
        raise AssertionError(f"{step} failed: {json.dumps(details, ensure_ascii=False)}")


def _assert_paths(project_root: Path, paths: Iterable[str]) -> None:
    missing = [path for path in paths if not (project_root / path).exists()]
    if missing:
        raise AssertionError(f"Missing expected artifacts: {missing}")


def _assert_sample_pipeline_state(sample_id: str, imported, analyzed, coverage_report: Dict[str, Any]) -> Dict[str, Any]:
    imported_chunks = int(imported.structured_output.get("total_chunks") or 0)
    analyzed_total = int(analyzed.structured_output.get("total_chunks") or 0)
    analyzed_chunks = int(analyzed.structured_output.get("analyzed_chunks") or 0)
    failed_chunks = int(analyzed.structured_output.get("failed_chunks") or 0)
    coverage_complete = bool(coverage_report.get("analysis_coverage", {}).get("is_complete"))

    if imported_chunks <= 0:
        raise AssertionError(f"{sample_id} did not reach CHUNKED semantic state: no chunks imported")
    if analyzed_total <= 0 or analyzed_chunks != analyzed_total or failed_chunks:
        raise AssertionError(
            f"{sample_id} did not reach ANALYZED semantic state: "
            f"{analyzed_chunks}/{analyzed_total} chunks analyzed, failed={failed_chunks}"
        )
    if not coverage_complete:
        raise AssertionError(f"{sample_id} coverage report is not complete")

    return {
        "after_import": "CHUNKED",
        "after_analysis": "ANALYZED",
        "imported_chunks": imported_chunks,
        "analyzed_chunks": analyzed_chunks,
        "failed_chunks": failed_chunks,
        "coverage_complete": coverage_complete,
    }


async def run_smoke(workspace: Path, project_id: str = "smoke_mvp_project") -> Dict[str, Any]:
    workspace = workspace.resolve()
    sample_paths = _write_raw_samples(workspace, project_id)
    project_root = workspace / "projects" / project_id

    with _temporary_settings(workspace):
        import_agent = SampleImportAgent()
        analysis_agent = FullTextAnalysisAgent()
        coverage_agent = CoverageCheckAgent()
        summary_agent = BookSummaryAgent()
        synthesis_agent = CrossBookSynthesisAgent()
        skill_agent = SkillGeneratorAgent()

        sample_summaries: List[Dict[str, Any]] = []
        for index, sample_id in enumerate(SAMPLE_TEXTS, start=1):
            imported = await import_agent.run(_request(
                task_id=f"smoke_{index}_import",
                project_id=project_id,
                task_type="sample_import",
                input_refs={"sample_id": sample_id, "file_path": sample_paths[sample_id]},
                parameters={"sample_id": sample_id, "file_path": sample_paths[sample_id]},
            ))
            _assert_response_success(imported, f"{sample_id} import")

            analyzed = await analysis_agent.run(_request(
                task_id=f"smoke_{index}_analysis",
                project_id=project_id,
                task_type="full_text_analysis",
                input_refs={"sample_id": sample_id},
                parameters={"sample_id": sample_id},
            ))
            _assert_response_success(analyzed, f"{sample_id} full_text_analysis")

            coverage = await coverage_agent.run(_request(
                task_id=f"smoke_{index}_coverage",
                project_id=project_id,
                task_type="coverage_check",
                input_refs={"sample_id": sample_id},
                parameters={"sample_id": sample_id},
            ))
            _assert_response_success(coverage, f"{sample_id} coverage_check")

            summarized = await summary_agent.run(_request(
                task_id=f"smoke_{index}_book_summary",
                project_id=project_id,
                task_type="book_summary",
                input_refs={"sample_id": sample_id},
                parameters={"sample_id": sample_id},
            ))
            _assert_response_success(summarized, f"{sample_id} book_summary")

            manifest = _read_json(project_root / "samples" / "manifests" / f"{sample_id}_manifest.json")
            coverage_report = _read_json(project_root / "analysis" / "coverage" / f"{sample_id}_coverage.json")
            pipeline_state = _assert_sample_pipeline_state(sample_id, imported, analyzed, coverage_report)
            sample_summaries.append({
                "sample_id": sample_id,
                "title": manifest["title"],
                "total_chunks": manifest["total_chunks"],
                "coverage_status": coverage_report["status"],
                "analysis_coverage_ratio": coverage_report["analysis_coverage"]["coverage_ratio"],
                "pipeline_state": pipeline_state,
            })

        synthesized = await synthesis_agent.run(_request(
            task_id="smoke_cross_book",
            project_id=project_id,
            task_type="cross_book_synthesis",
            input_refs={"sample_ids": list(SAMPLE_TEXTS)},
            parameters={"sample_ids": list(SAMPLE_TEXTS)},
        ))
        _assert_response_success(synthesized, "cross_book_synthesis")

        skills = await skill_agent.run(_request(
            task_id="smoke_skill_generation",
            project_id=project_id,
            task_type="skill_generation",
            parameters={"skill_types": ["writing", "outline", "review"]},
        ))
        _assert_response_success(skills, "skill_generation")

    expected_paths = [
        "analysis/cross_book/cross_book_synthesis.md",
        "analysis/cross_book/technique_summary.json",
        "skills/enabled.yaml",
        "skills/local/writing_skill.md",
        "skills/local/outline_skill.md",
        "skills/local/review_skill.md",
    ]
    for sample_id in SAMPLE_TEXTS:
        expected_paths.extend([
            f"samples/manifests/{sample_id}_manifest.json",
            f"samples/chunks/{sample_id}",
            f"analysis/per_chunk/{sample_id}/summary.json",
            f"analysis/coverage/{sample_id}_coverage.json",
            f"analysis/per_book/{sample_id}_report.md",
        ])
    _assert_paths(project_root, expected_paths)

    enabled_yaml = (project_root / "skills" / "enabled.yaml").read_text(encoding="utf-8")
    for skill_name in ("writing_skill", "outline_skill", "review_skill"):
        if skill_name not in enabled_yaml:
            raise AssertionError(f"{skill_name} missing from skills/enabled.yaml")

    return {
        "status": "passed",
        "project_id": project_id,
        "workspace": str(workspace),
        "sample_count": len(SAMPLE_TEXTS),
        "samples": sample_summaries,
        "artifact_checks": expected_paths,
        "pipeline_checks": {
            "sample_import": "CHUNKED",
            "full_text_analysis": "ANALYZED",
            "required_artifacts": [
                "analysis/per_chunk",
                "analysis/per_book",
                "analysis/cross_book",
                "skills/enabled.yaml",
            ],
        },
        "skill_count": skills.structured_output.get("skill_count"),
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the offline MVP pipeline smoke check.")
    parser.add_argument(
        "--workspace",
        type=Path,
        default=None,
        help="Workspace directory to use. Defaults to a temporary directory.",
    )
    parser.add_argument(
        "--project-id",
        default="smoke_mvp_project",
        help="Project id to create inside the workspace.",
    )
    parser.add_argument(
        "--keep-workspace",
        action="store_true",
        help="Do not delete the temporary workspace after a successful run.",
    )
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    temp_dir: Optional[str] = None
    workspace = args.workspace
    if workspace is None:
        temp_dir = tempfile.mkdtemp(prefix="novel_smoke_")
        workspace = Path(temp_dir)

    try:
        result = asyncio.run(run_smoke(workspace, args.project_id))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(f"Smoke check failed: {exc}", file=sys.stderr)
        return 1
    finally:
        if temp_dir and not args.keep_workspace:
            shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
