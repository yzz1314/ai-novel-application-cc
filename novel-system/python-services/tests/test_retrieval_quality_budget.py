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
                    "林墨在雪夜进入玄门试炼，玉牌发热，苏青提示天火城异变。"
                    "这一段强调压迫感、悬念钩子、人物关系和场景节奏。"
                ) * 3,
            }, ensure_ascii=False),
            encoding="utf-8",
        )

    memory_dir = project_root / "memory"
    memory_dir.mkdir(parents=True)
    (memory_dir / "canon.md").write_text("林墨持有雪夜玉牌，玄门试炼不能提前暴露终局。", encoding="utf-8")
    (memory_dir / "characters.md").write_text("林墨谨慎，苏青与林墨互为盟友。", encoding="utf-8")

    skills_dir = project_root / "skills" / "local"
    skills_dir.mkdir(parents=True)
    (skills_dir / "scene.skill.md").write_text(
        "玄幻试炼场景要先压迫，再给短促反击，用物件异动制造章末悬念。",
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
                    "name": "林墨",
                    "properties": {"description": "持有雪夜玉牌的少年"},
                },
                {
                    "node_id": "org_xuanmen",
                    "node_type": "organization",
                    "name": "玄门",
                    "properties": {"description": "试炼组织"},
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
                chapter_title="雪夜试炼",
                plot_goal="林墨 玄门 试炼 玉牌 悬念",
                character_development="林墨和苏青建立信任",
                conflict="天火城异变牵动玄门试炼",
                appeal_point="压迫感和章末钩子",
            ),
            previous_context="上一章林墨捡到玉牌。",
            top_k=4,
        )
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert context_pack["quality_evaluation"]["score"] >= 50
    assert context_pack["quality_evaluation"]["metrics"]["returned_count"] > 0
    assert context_pack["citation_budget"]["usage"]["selected_result_count"] <= 3
    assert context_pack["citation_budget"]["usage"]["prompt_section_chars"] <= 1600
    assert all(item["citation_id"].startswith("R") for item in context_pack["retrieval_results"])
    assert all(len(item["snippet"]) <= 93 for item in context_pack["retrieval_results"])

    saved_pack = json.loads((config_dir / "bm25" / "context_packs" / "book_a_v1_c2.json").read_text(encoding="utf-8"))
    hybrid_summary = json.loads((config_dir / "hybrid" / "index_summary.json").read_text(encoding="utf-8"))
    assert saved_pack["quality_evaluation"]["status"] in {"good", "needs_review", "poor"}
    assert saved_pack["citation_budget"]["usage"]["selected_citation_ids"]
    assert hybrid_summary["quality_evaluation"]["score"] == context_pack["quality_evaluation"]["score"]
    assert hybrid_summary["citation_budget"]["usage"]["selected_result_count"] == context_pack["citation_budget"]["usage"]["selected_result_count"]


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
            user_input="林墨 玄门 试炼 玉牌",
            parameters={"top_k": 4},
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert response.status == "success"
    assert response.structured_output["quality_evaluation"]["score"] >= 50
    assert "citation_budget" in response.structured_output

    report = json.loads((project_root / "indexes" / "retrieval_index_report.json").read_text(encoding="utf-8"))
    hybrid_summary = json.loads((project_root / "indexes" / "hybrid" / "index_summary.json").read_text(encoding="utf-8"))
    assert report["quality_evaluation"]["metrics"]["returned_count"] > 0
    assert report["citation_budget"]["usage"]["selected_result_count"] > 0
    assert hybrid_summary["quality_evaluation"]["score"] == report["quality_evaluation"]["score"]
