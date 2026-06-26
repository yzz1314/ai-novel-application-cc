import json
import sys
import types

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.memory_query_agent import MemoryQueryAgent
from schemas.agent_request import AgentRequest
from config import settings


@pytest.mark.asyncio
async def test_continuity_check_detects_future_timeline_and_missing_appearance(tmp_path):
    project_id = "proj_memory_check"
    book_id = "default"
    project_root = tmp_path / "projects" / project_id
    chapter_dir = project_root / "novel" / "chapters" / "drafts" / book_id / "volume_1"
    memory_dir = project_root / "memory"
    chapter_dir.mkdir(parents=True)
    memory_dir.mkdir(parents=True)

    chapter = {
        "chapter_id": "chapter_2",
        "book_id": book_id,
        "volume_number": 1,
        "chapter_number": 2,
        "chapter_title": "提前的风暴",
        "content": "林墨推开石门，看见天火城毁灭。"
    }
    (chapter_dir / "chapter_2.json").write_text(
        json.dumps(chapter, ensure_ascii=False),
        encoding="utf-8"
    )
    (memory_dir / "characters.json").write_text(
        json.dumps([
            {
                "character_id": "char_linmo",
                "name": "林墨",
                "role": "protagonist",
                "description": "主角",
                "current_status": {},
                "appearances": [1],
                "first_mentioned": 1,
                "last_updated": 1,
                "important_events": [],
                "status_history": []
            }
        ], ensure_ascii=False),
        encoding="utf-8"
    )
    (memory_dir / "world_settings.json").write_text("[]", encoding="utf-8")
    (memory_dir / "timeline.json").write_text(
        json.dumps([
            {
                "event_id": "event_future",
                "chapter": 5,
                "event_type": "disaster",
                "title": "天火城毁灭",
                "description": "天火城毁灭",
                "involved_characters": ["林墨"],
                "consequences": ["主角失去退路"]
            }
        ], ensure_ascii=False),
        encoding="utf-8"
    )

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        agent = MemoryQueryAgent()
        response = await agent.run(AgentRequest(
            task_id="task_continuity",
            project_id=project_id,
            task_type="continuity_check",
            parameters={
                "project_id": project_id,
                "book_id": book_id,
                "chapter_id": "chapter_2",
                "volume_number": 1,
                "chapter_number": 2
            }
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert response.status == "success"
    assert response.structured_output["has_issues"] is True
    assert response.structured_output["minor_count"] == 1
    assert response.structured_output["critical_count"] == 1
    issue_titles = {issue["title"] for issue in response.structured_output["issues"]}
    assert "人物出场记录未更新" in issue_titles
    assert "未来时间线事件提前发生" in issue_titles
    assert response.output_refs
