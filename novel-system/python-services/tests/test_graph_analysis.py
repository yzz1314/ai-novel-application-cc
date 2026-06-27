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
    project_root = tmp_path / "projects" / project_id
    memory_dir = tmp_path / "projects" / project_id / "memory"
    memory_dir.mkdir(parents=True)
    outline_dir = project_root / "novel" / "outline"
    outline_dir.mkdir(parents=True)
    cross_book_dir = project_root / "analysis" / "cross_book"
    cross_book_dir.mkdir(parents=True)
    chapter_dir = project_root / "novel" / "chapters" / "drafts" / "default" / "volume_1"
    chapter_dir.mkdir(parents=True)

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
    (outline_dir / "default_outline.json").write_text(
        json.dumps({
            "book_id": "default",
            "project_id": project_id,
            "book_title": "天火试炼",
            "genre": "玄幻",
            "target_word_count": 100000,
            "core_concept": "林墨在天火城追查旧谜",
            "world_view": "玄门与天火城并立",
            "main_conflict": "林墨必须在玄门试炼中揭开天火旧谜",
            "characters": [
                {
                    "name": "林墨",
                    "role": "主角",
                    "description": "追查天火旧谜的少年",
                    "introduction_chapter": 1,
                }
            ],
            "world_settings": [
                {
                    "name": "天火城",
                    "category": "地点",
                    "description": "旧谜发生地",
                    "related_entities": ["林墨"],
                }
            ],
            "long_term_suspense": ["天火旧谜"],
            "volumes": [
                {
                    "volume_number": 1,
                    "volume_title": "玄门初试",
                    "main_goal": "进入玄门",
                    "character_growth": "林墨学会承担旧谜代价",
                    "external_conflict": "玄门试炼与天火城异变交叠",
                    "resolution": "通过试炼",
                    "chapters": [
                        {
                            "chapter_number": 1,
                            "chapter_title": "旧谜入城",
                            "target_word_count": 3000,
                            "plot_goal": "林墨进入天火城，发现天火旧谜与玄门试炼相关",
                            "character_development": "林墨开始主动追查",
                            "info_reveal": "天火城藏有旧谜线索",
                            "scenes": [{"scene_name": "天火城门", "description": "林墨抵达天火城"}],
                            "conflict": "林墨被玄门巡使盘问",
                            "appeal_point": "以弱破局",
                            "suspense": "天火旧谜为何指向玄门试炼",
                            "connect_previous": "",
                            "lead_to_next": "林墨决定参加玄门试炼",
                            "core_goal": "建立天火旧谜与玄门试炼的联系",
                            "must_write": ["林墨抵达天火城", "天火旧谜露出线索"],
                            "allowed_progress": ["玄门试炼只露出报名入口"],
                            "must_not_write": ["不得揭开天火旧谜真相"],
                            "reserved_for_future": {"chapter_2": "正式进入玄门试炼"},
                            "stop_point": "停在报名试炼前",
                            "ending_hook": "试炼令牌浮现天火纹",
                        }
                    ],
                }
            ],
            "total_volumes": 1,
            "total_chapters": 1,
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    (cross_book_dir / "technique_summary.json").write_text(
        json.dumps({
            "scene_techniques": {"钟声压迫": 3},
            "prose_techniques": {},
            "outline_techniques": {"以弱破局": 4},
            "conflict_types": {"external": 2},
            "appeal_types": {"逆袭": 1},
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    (chapter_dir / "chapter_1.json").write_text(
        json.dumps({
            "chapter_id": "chapter_1",
            "book_id": "default",
            "volume_number": 1,
            "chapter_number": 1,
            "chapter_title": "旧谜入城",
            "content": "林墨踏入天火城时，玄门试炼的钟声刚刚响起。天火旧谜的纹路在令牌上复现，他意识到玄门试炼并非普通考核。钟声压迫让场景更紧。",
            "word_count": 52,
            "quality_score": 82,
            "review_status": "reviewed",
            "version": 1,
        }, ensure_ascii=False),
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
    nodes_by_id = {node["node_id"]: node for node in graph["nodes"]}
    assert "outline_chapter_1_1" in nodes_by_id
    assert nodes_by_id["outline_chapter_1_1"]["node_type"] == "chapter_outline"
    assert any(
        edge["source_id"] == "outline_chapter_1_1"
        and edge["target_id"] == "char_linmo"
        and edge["edge_type"] == "involves_character"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "outline_chapter_1_1"
        and edge["target_id"] == "loc_tianhuo"
        and edge["edge_type"] == "involves_setting"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "outline_chapter_1_1"
        and edge["target_id"] == "suspense_fire_secret"
        and edge["edge_type"] == "sets_up_foreshadowing"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "outline_chapter_1_1"
        and edge["target_id"] == "plot_trial"
        and edge["edge_type"] == "advances_plot"
        for edge in graph["edges"]
    )
    technique_nodes = {
        node["name"]: node
        for node in graph["nodes"]
        if node["node_type"] == "technique"
    }
    assert technique_nodes["以弱破局"]["properties"]["total_count"] == 4
    assert technique_nodes["钟声压迫"]["properties"]["categories"] == ["scene"]
    assert any(
        edge["source_id"] == "outline_chapter_1_1"
        and edge["target_id"] == technique_nodes["以弱破局"]["node_id"]
        and edge["edge_type"] == "uses_technique"
        for edge in graph["edges"]
    )
    assert "chapter_content_1_1" in nodes_by_id
    assert nodes_by_id["chapter_content_1_1"]["node_type"] == "chapter_content"
    assert any(
        edge["source_id"] == "chapter_content_1_1"
        and edge["target_id"] == "outline_chapter_1_1"
        and edge["edge_type"] == "implements_outline"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "chapter_content_1_1"
        and edge["target_id"] == "char_linmo"
        and edge["edge_type"] == "mentions_character"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "chapter_content_1_1"
        and edge["target_id"] == "loc_tianhuo"
        and edge["edge_type"] == "mentions_setting"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "chapter_content_1_1"
        and edge["target_id"] == "suspense_fire_secret"
        and edge["edge_type"] == "mentions_foreshadowing"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "chapter_content_1_1"
        and edge["target_id"] == "plot_trial"
        and edge["edge_type"] == "mentions_plot"
        for edge in graph["edges"]
    )
    assert any(
        edge["source_id"] == "chapter_content_1_1"
        and edge["target_id"] == technique_nodes["钟声压迫"]["node_id"]
        and edge["edge_type"] == "uses_technique"
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
