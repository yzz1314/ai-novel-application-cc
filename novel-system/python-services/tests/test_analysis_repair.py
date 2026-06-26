import json
import sys
import types
from pathlib import Path

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.analysis_repair_agent import AnalysisRepairAgent
from config import settings
from schemas.agent_request import AgentRequest


def write_sample_with_missing_and_failed_analysis(tmp_path: Path, project_id: str, sample_id: str):
    project_root = tmp_path / "projects" / project_id
    manifest_dir = project_root / "samples" / "manifests"
    chunks_dir = project_root / "samples" / "chunks" / sample_id
    analysis_dir = project_root / "analysis" / "per_chunk" / sample_id
    manifest_dir.mkdir(parents=True)
    chunks_dir.mkdir(parents=True)
    analysis_dir.mkdir(parents=True)

    (manifest_dir / f"{sample_id}_manifest.json").write_text(
        json.dumps({
            "sample_id": sample_id,
            "title": "Repair Test Sample",
            "total_chars": 300,
            "total_chapters": 1,
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    for index in range(3):
        (chunks_dir / f"chunk_{index}.json").write_text(
            json.dumps({
                "id": f"chunk_{index}",
                "text": "The apprentice enters the gate while the jade token warms. " * 3,
                "start_offset": index * 100,
                "end_offset": (index + 1) * 100,
                "chapter_range": "Chapter 1",
            }, ensure_ascii=False),
            encoding="utf-8",
        )

    (analysis_dir / "chunk_0_analysis.json").write_text(
        json.dumps({
            "chunk_id": "chunk_0",
            "analysis": {"summary": "already done"},
            "analyzed_at": "2026-01-01T00:00:00",
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    (analysis_dir / "chunk_1_analysis.json").write_text(
        json.dumps({
            "chunk_id": "chunk_1",
            "error": "mock failure",
        }, ensure_ascii=False),
        encoding="utf-8",
    )


@pytest.mark.asyncio
async def test_analysis_repair_retries_missing_and_failed_chunks(tmp_path):
    project_id = "proj_analysis_repair"
    sample_id = "sample_repair"
    write_sample_with_missing_and_failed_analysis(tmp_path, project_id, sample_id)

    original_base_path = settings.PROJECT_BASE_PATH
    original_mock = settings.MOCK_LLM
    settings.PROJECT_BASE_PATH = str(tmp_path)
    settings.MOCK_LLM = True
    try:
        agent = AnalysisRepairAgent()
        response = await agent.run(AgentRequest(
            task_id="task_analysis_repair",
            project_id=project_id,
            task_type="analysis_repair",
            input_refs={"sample_id": sample_id},
            parameters={"sample_id": sample_id},
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
        settings.MOCK_LLM = original_mock

    assert response.status == "success"
    assert response.structured_output["target_count"] == 2
    assert response.structured_output["repaired_count"] == 2
    assert response.structured_output["remaining_missing_count"] == 0
    assert response.structured_output["remaining_failed_count"] == 0

    project_root = tmp_path / "projects" / project_id
    for chunk_id in ["chunk_0", "chunk_1", "chunk_2"]:
        analysis = json.loads(
            (project_root / "analysis" / "per_chunk" / sample_id / f"{chunk_id}_analysis.json")
            .read_text(encoding="utf-8")
        )
        assert analysis["chunk_id"] == chunk_id
        assert not analysis.get("error")

    report_path = project_root / response.structured_output["report_path"]
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["status"] == "passed"
    assert sorted(report["repaired_chunks"]) == ["chunk_1", "chunk_2"]

    coverage = json.loads(
        (project_root / "analysis" / "coverage" / f"{sample_id}_coverage.json").read_text(encoding="utf-8")
    )
    assert coverage["status"] == "passed"
    assert coverage["analysis_coverage"]["is_complete"] is True


@pytest.mark.asyncio
async def test_analysis_repair_honors_target_chunk_ids(tmp_path):
    project_id = "proj_analysis_repair_targeted"
    sample_id = "sample_repair_targeted"
    write_sample_with_missing_and_failed_analysis(tmp_path, project_id, sample_id)

    original_base_path = settings.PROJECT_BASE_PATH
    original_mock = settings.MOCK_LLM
    settings.PROJECT_BASE_PATH = str(tmp_path)
    settings.MOCK_LLM = True
    try:
        agent = AnalysisRepairAgent()
        response = await agent.run(AgentRequest(
            task_id="task_analysis_repair_targeted",
            project_id=project_id,
            task_type="analysis_repair",
            input_refs={"sample_id": sample_id},
            parameters={
                "sample_id": sample_id,
                "chunk_ids": ["chunk_1"],
            },
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
        settings.MOCK_LLM = original_mock

    assert response.status == "partial"
    assert response.structured_output["target_count"] == 1
    assert response.structured_output["repaired_count"] == 1
    assert response.structured_output["remaining_missing_count"] == 1
    assert response.structured_output["remaining_failed_count"] == 0

    project_root = tmp_path / "projects" / project_id
    analysis_dir = project_root / "analysis" / "per_chunk" / sample_id
    assert (analysis_dir / "chunk_1_analysis.json").exists()
    assert not (analysis_dir / "chunk_2_analysis.json").exists()

    report_path = project_root / response.structured_output["report_path"]
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["status"] == "needs_attention"
    assert report["requested_chunk_ids"] == ["chunk_1"]
    assert report["repair_targets"] == ["chunk_1"]
    assert report["remaining_missing_chunks"] == ["chunk_2"]
