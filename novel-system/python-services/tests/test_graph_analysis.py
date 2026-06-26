import json
import sys
import types

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.graph_builder_agent import GraphBuilderAgent
from config import settings
from schemas.agent_request import AgentRequest


@pytest.mark.asyncio
async def test_graph_build_writes_advanced_analysis(tmp_path):
    project_id = "proj_graph_analysis"
    memory_dir = tmp_path / "projects" / project_id / "memory"
    memory_dir.mkdir(parents=True)

    (memory_dir / "characters.json").write_text(
        json.dumps([
            {
                "character_id": "char_linmo",
                "name": "林墨",
                "role": "protagonist",
                "relationships": {"苏青": "ally", "玄门": "member_of"},
                "first_mentioned": 1,
                "last_updated": 5,
            },
            {
                "character_id": "char_suqing",
                "name": "苏青",
                "role": "support",
                "relationships": {"天火城": "born_in"},
                "first_mentioned": 2,
                "last_updated": 5,
            },
        ], ensure_ascii=False),
        encoding="utf-8",
    )
    (memory_dir / "world_settings.json").write_text(
        json.dumps([
            {
                "setting_id": "org_xuanmen",
                "name": "玄门",
                "category": "organization",
                "related_characters": ["林墨"],
                "first_mentioned": 1,
                "last_updated": 5,
            },
            {
                "setting_id": "loc_tianhuo",
                "name": "天火城",
                "category": "location",
                "related_characters": ["苏青"],
                "first_mentioned": 2,
                "last_updated": 5,
            },
        ], ensure_ascii=False),
        encoding="utf-8",
    )
    (memory_dir / "plots.json").write_text(
        json.dumps([
            {
                "plot_id": "plot_trial",
                "title": "玄门试炼",
                "plot_type": "training",
                "involved_characters": ["林墨", "苏青"],
                "involved_settings": ["玄门"],
                "start_chapter": 3,
                "end_chapter": 5,
            }
        ], ensure_ascii=False),
        encoding="utf-8",
    )
    (memory_dir / "suspenses.json").write_text("[]", encoding="utf-8")
    (memory_dir / "timeline.json").write_text(
        json.dumps([
            {
                "event_id": "event_fire",
                "title": "天火城异变",
                "event_type": "crisis",
                "involved_characters": ["苏青"],
                "involved_settings": ["天火城"],
                "chapter": 4,
            }
        ], ensure_ascii=False),
        encoding="utf-8",
    )

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        agent = GraphBuilderAgent()
        response = await agent.run(AgentRequest(
            task_id="task_graph_analysis",
            project_id=project_id,
            task_type="graph_build",
            parameters={
                "project_id": project_id,
                "book_id": "default",
                "include_characters": True,
                "include_locations": True,
                "include_organizations": True,
                "include_items": True,
                "include_skills": True,
            },
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert response.status == "success"
    assert response.structured_output["connected_components"] == 1
    assert response.structured_output["top_nodes_by_centrality"]

    graph_path = tmp_path / "projects" / project_id / "graph" / "default_graph.json"
    graph = json.loads(graph_path.read_text(encoding="utf-8"))
    assert graph["statistics"]["connected_components"] == 1
    assert graph["statistics"]["top_nodes_by_centrality"]
    assert graph["analysis"]["relationship_analysis"]
    assert graph["analysis"]["key_paths"]
    assert "warnings" in graph["analysis"]
