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
    (memory_dir / "suspenses.json").write_text(
        json.dumps([
            {
                "suspense_id": "suspense_fire_secret",
                "title": "天火旧谜",
                "suspense_type": "mystery",
                "question": "天火城异变源头是谁",
                "involved_characters": ["林墨"],
                "involved_settings": ["天火城"],
                "resolved_by_plot": "玄门试炼",
                "set_chapter": 2,
                "resolved_chapter": 5,
            }
        ], ensure_ascii=False),
        encoding="utf-8",
    )
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
    edge_types = {edge["edge_type"] for edge in graph["edges"]}
    assert "resolved_by" in edge_types
    assert any(
        edge["source_id"] == "suspense_fire_secret"
        and edge["target_id"] == "plot_trial"
        and edge["edge_type"] == "resolved_by"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "suspense_fire_secret"
        and edge["target_id"] == "char_linmo"
        and edge["edge_type"] == "involves_character"
        for edge in graph["edges"]
    )


@pytest.mark.asyncio
async def test_graph_incremental_build_merges_existing_graph(tmp_path):
    project_id = "proj_graph_incremental"
    project_root = tmp_path / "projects" / project_id
    memory_dir = project_root / "memory"
    graph_dir = project_root / "graph"
    memory_dir.mkdir(parents=True)
    graph_dir.mkdir(parents=True)

    (graph_dir / "graph.json").write_text(
        json.dumps({
            "graph_id": "graph_previous",
            "book_id": "default",
            "nodes": [
                {
                    "node_id": "char_linmo",
                    "node_type": "character",
                    "name": "林墨",
                    "properties": {"role": "old_role", "description": "旧描述"},
                    "first_mentioned": 1,
                    "last_updated": 1,
                    "created_at": "2026-01-01T00:00:00",
                    "updated_at": "2026-01-01T00:00:00",
                },
                {
                    "node_id": "legacy_secret",
                    "node_type": "plot",
                    "name": "旧伏笔",
                    "properties": {"description": "只存在于旧图谱"},
                    "first_mentioned": 1,
                    "last_updated": 1,
                    "created_at": "2026-01-01T00:00:00",
                    "updated_at": "2026-01-01T00:00:00",
                },
            ],
            "edges": [
                {
                    "edge_id": "edge_legacy",
                    "edge_type": "foreshadows",
                    "source_id": "legacy_secret",
                    "target_id": "char_linmo",
                    "properties": {},
                    "weight": 1.0,
                    "established_chapter": 1,
                    "last_mentioned": 1,
                    "created_at": "2026-01-01T00:00:00",
                    "updated_at": "2026-01-01T00:00:00",
                }
            ],
            "node_count": 2,
            "edge_count": 1,
        }, ensure_ascii=False),
        encoding="utf-8",
    )

    (memory_dir / "characters.json").write_text(
        json.dumps([
            {
                "character_id": "char_linmo",
                "name": "林墨",
                "role": "protagonist",
                "description": "新描述",
                "relationships": {"苏青": "ally"},
                "first_mentioned": 1,
                "last_updated": 6,
            },
            {
                "character_id": "char_suqing",
                "name": "苏青",
                "role": "support",
                "relationships": {},
                "first_mentioned": 2,
                "last_updated": 6,
            },
        ], ensure_ascii=False),
        encoding="utf-8",
    )
    (memory_dir / "world_settings.json").write_text("[]", encoding="utf-8")
    (memory_dir / "plots.json").write_text("[]", encoding="utf-8")
    (memory_dir / "suspenses.json").write_text("[]", encoding="utf-8")
    (memory_dir / "timeline.json").write_text("[]", encoding="utf-8")

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        response = await GraphBuilderAgent().run(AgentRequest(
            task_id="task_graph_incremental",
            project_id=project_id,
            task_type="graph_build",
            parameters={
                "project_id": project_id,
                "book_id": "default",
                "incremental": True,
            },
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert response.status == "success"
    summary = response.structured_output["incremental_summary"]
    assert summary["used_existing_graph"] is True
    assert summary["nodes_created"] == 1
    assert summary["nodes_updated"] == 1
    assert summary["nodes_preserved_from_previous"] == 1
    assert summary["edges_created"] == 1
    assert summary["edges_preserved_from_previous"] == 1

    graph = json.loads((graph_dir / "default_graph.json").read_text(encoding="utf-8"))
    nodes_by_id = {node["node_id"]: node for node in graph["nodes"]}
    edge_ids = {edge["edge_id"] for edge in graph["edges"]}

    assert set(nodes_by_id) == {"char_linmo", "char_suqing", "legacy_secret"}
    assert nodes_by_id["char_linmo"]["properties"]["role"] == "protagonist"
    assert nodes_by_id["legacy_secret"]["properties"]["description"] == "只存在于旧图谱"
    assert "edge_legacy" in edge_ids
    assert any(edge_id.startswith("edge_char_linmo_ally_char_suqing") for edge_id in edge_ids)
    assert graph["analysis"]["incremental_build"]["preserved_node_ids"] == ["legacy_secret"]
