import json
import sys
import types
from types import SimpleNamespace

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.retrieval_index_agent import RetrievalIndexAgent
from config import settings
from retrieval.context_builder import ContextBuilder
from schemas.agent_request import AgentRequest


def write_project_documents(tmp_path, project_id):
    project_root = tmp_path / "projects" / project_id
    chunk_dir = project_root / "samples" / "chunks" / "sample_a"
    chunk_dir.mkdir(parents=True)
    for index in range(5):
        (chunk_dir / f"chunk_{index}.json").write_text(
            json.dumps({
                "chunk_id": f"chunk_{index}",
                "sample_id": "sample_a",
                "text": (
                    "Lin Mo enters the Xuanmen trial on a snowy night. "
                    "The jade token heats in his palm while Suqing warns him "
                    "that the hidden archive is a pressure hook and a scene clue. "
                ) * 3,
            }, ensure_ascii=False),
            encoding="utf-8",
        )

    memory_dir = project_root / "memory"
    memory_dir.mkdir(parents=True)
    (memory_dir / "canon.md").write_text(
        "Lin Mo owns the snow-night jade token. Xuanmen trial secrets must not reveal the ending early.",
        encoding="utf-8",
    )
    (memory_dir / "characters.md").write_text(
        "Lin Mo is cautious. Suqing is his ally and notices hidden token clues.",
        encoding="utf-8",
    )

    skills_dir = project_root / "skills" / "local"
    skills_dir.mkdir(parents=True)
    (skills_dir / "scene.skill.md").write_text(
        "Trial scenes should start with pressure, then reveal a short clue, and end on a hook.",
        encoding="utf-8",
    )

    graph_dir = project_root / "graph"
    graph_dir.mkdir(parents=True)
    (graph_dir / "graph.json").write_text(
        json.dumps({
            "nodes": [
                {
                    "node_id": "char_linmo",
                    "node_type": "character",
                    "name": "Lin Mo",
                    "properties": {"description": "The young holder of the jade token."},
                },
                {
                    "node_id": "org_xuanmen",
                    "node_type": "organization",
                    "name": "Xuanmen",
                    "properties": {"description": "The sect that runs the trial."},
                },
            ],
            "edges": [
                {"source_id": "char_linmo", "target_id": "org_xuanmen", "relation": "joins"}
            ],
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    return project_root


@pytest.mark.asyncio
async def test_context_builder_writes_quality_evaluation_and_citation_budget(tmp_path):
    project_id = "proj_retrieval_budget"
    write_project_documents(tmp_path, project_id)
    config_dir = tmp_path / "projects" / project_id / "indexes"
    config_dir.mkdir(parents=True, exist_ok=True)
    (config_dir / "retrieval_config.json").write_text(
        json.dumps({
            "top_k": 4,
            "max_context_chars": 1600,
            "max_retrieval_results": 3,
            "max_retrieval_chars": 520,
            "max_result_chars": 90,
            "max_sample_quote_chars": 45,
            "max_results_per_source_type": 2,
        }, ensure_ascii=False),
        encoding="utf-8",
    )

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        context_pack = await ContextBuilder(project_id).build_chapter_context(
            book_id="book_a",
            volume_number=1,
            chapter_number=2,
            chapter_outline=SimpleNamespace(
                chapter_title="Snow Token Trial",
                plot_goal="Lin Mo Xuanmen trial jade token pressure clue",
                character_development="Lin Mo and Suqing learn to trust each other",
                conflict="The hidden archive pulls the trial into danger",
                appeal_point="pressure, clue, and chapter-end hook",
            ),
            previous_context="The previous chapter ended with Lin Mo grabbing the jade token.",
            top_k=4,
        )
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert context_pack["quality_evaluation"]["score"] >= 50
    assert context_pack["quality_evaluation"]["metrics"]["returned_count"] > 0
    assert context_pack["citation_budget"]["usage"]["selected_result_count"] <= 3
    assert context_pack["citation_budget"]["usage"]["prompt_section_chars"] <= 1600
    assert context_pack["cache_status"]["cache_status"] == "new"
    assert all(item["citation_id"].startswith("R") for item in context_pack["retrieval_results"])
    assert all(len(item["snippet"]) <= 93 for item in context_pack["retrieval_results"])

    saved_pack = json.loads((config_dir / "bm25" / "context_packs" / "book_a_v1_c2.json").read_text(encoding="utf-8"))
    hybrid_summary = json.loads((config_dir / "hybrid" / "index_summary.json").read_text(encoding="utf-8"))
    assert saved_pack["quality_evaluation"]["status"] in {"good", "needs_review", "poor"}
    assert saved_pack["citation_budget"]["usage"]["selected_citation_ids"]
    assert hybrid_summary["quality_evaluation"]["score"] == context_pack["quality_evaluation"]["score"]
    assert hybrid_summary["citation_budget"]["usage"]["selected_result_count"] == context_pack["citation_budget"]["usage"]["selected_result_count"]
    assert hybrid_summary["cache_status"]["index_fingerprint"] == context_pack["cache_status"]["index_fingerprint"]


@pytest.mark.asyncio
async def test_retrieval_index_report_includes_quality_and_budget(tmp_path):
    project_id = "proj_retrieval_index_report"
    project_root = write_project_documents(tmp_path, project_id)

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        response = await RetrievalIndexAgent().run(AgentRequest(
            task_id="task_retrieval_quality",
            project_id=project_id,
            task_type="retrieval_index",
            user_input="Lin Mo Xuanmen trial jade token",
            parameters={"top_k": 4},
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert response.status == "success"
    assert response.structured_output["quality_evaluation"]["score"] >= 50
    assert "citation_budget" in response.structured_output
    assert response.structured_output["cache_status"]["cache_status"] == "new"

    report = json.loads((project_root / "indexes" / "retrieval_index_report.json").read_text(encoding="utf-8"))
    hybrid_summary = json.loads((project_root / "indexes" / "hybrid" / "index_summary.json").read_text(encoding="utf-8"))
    assert report["quality_evaluation"]["metrics"]["returned_count"] > 0
    assert report["citation_budget"]["usage"]["selected_result_count"] > 0
    assert report["cache_status"]["index_fingerprint"]
    assert hybrid_summary["quality_evaluation"]["score"] == report["quality_evaluation"]["score"]
    assert hybrid_summary["cache_status"]["index_fingerprint"] == report["cache_status"]["index_fingerprint"]


@pytest.mark.asyncio
async def test_retrieval_index_records_fingerprint_and_cache_invalidation(tmp_path):
    project_id = "proj_retrieval_cache"
    project_root = write_project_documents(tmp_path, project_id)
    chunk_path = project_root / "samples" / "chunks" / "sample_a" / "chunk_0.json"

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        first = await RetrievalIndexAgent().run(AgentRequest(
            task_id="task_retrieval_cache_first",
            project_id=project_id,
            task_type="retrieval_index",
            user_input="Lin Mo Xuanmen trial jade token",
            parameters={"top_k": 4},
        ))
        second = await RetrievalIndexAgent().run(AgentRequest(
            task_id="task_retrieval_cache_second",
            project_id=project_id,
            task_type="retrieval_index",
            user_input="Lin Mo Xuanmen trial jade token",
            parameters={"top_k": 4},
        ))

        chunk_data = json.loads(chunk_path.read_text(encoding="utf-8"))
        chunk_data["text"] += " New cache invalidation clue: Suqing discovers the hidden token."
        chunk_path.write_text(json.dumps(chunk_data, ensure_ascii=False), encoding="utf-8")

        third = await RetrievalIndexAgent().run(AgentRequest(
            task_id="task_retrieval_cache_third",
            project_id=project_id,
            task_type="retrieval_index",
            user_input="Lin Mo Xuanmen trial jade token",
            parameters={"top_k": 4},
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert first.status == "success"
    assert second.status == "success"
    assert third.status == "success"

    first_cache = first.structured_output["cache_status"]
    second_cache = second.structured_output["cache_status"]
    third_cache = third.structured_output["cache_status"]

    assert first_cache["cache_status"] == "new"
    assert second_cache["cache_status"] == "fresh"
    assert second_cache["index_fingerprint"] == first_cache["index_fingerprint"]
    assert third_cache["cache_status"] == "stale"
    assert third_cache["cache_invalidated"] is True
    assert third_cache["previous_index_fingerprint"] == second_cache["index_fingerprint"]
    assert third_cache["index_fingerprint"] != second_cache["index_fingerprint"]

    report = json.loads((project_root / "indexes" / "retrieval_index_report.json").read_text(encoding="utf-8"))
    bm25_summary = json.loads((project_root / "indexes" / "bm25" / "index_summary.json").read_text(encoding="utf-8"))
    vector_summary = json.loads((project_root / "indexes" / "vector" / "index_summary.json").read_text(encoding="utf-8"))
    hybrid_summary = json.loads((project_root / "indexes" / "hybrid" / "index_summary.json").read_text(encoding="utf-8"))

    assert report["cache_status"]["cache_status"] == "stale"
    assert bm25_summary["cache_status"]["index_fingerprint"] == third_cache["index_fingerprint"]
    assert vector_summary["cache_status"]["index_fingerprint"] == third_cache["index_fingerprint"]
    assert hybrid_summary["cache_status"]["index_fingerprint"] == third_cache["index_fingerprint"]
