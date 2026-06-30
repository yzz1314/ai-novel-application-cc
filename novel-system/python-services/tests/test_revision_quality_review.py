import json
import sys
import types
from pathlib import Path

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

PYTHON_ROOT = Path(__file__).resolve().parents[1]
if str(PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(PYTHON_ROOT))

from agents.revision_agent import RevisionAgent
from config import settings
from schemas.agent_request import AgentRequest


class FakeRevisionLLM:
    def __init__(self):
        self.calls = []

    async def generate_with_retry(self, prompt, response_format=None, **kwargs):
        self.calls.append({"prompt": prompt, "response_format": response_format, "kwargs": kwargs})
        if response_format == "json":
            return {
                "content": json.dumps({
                    "style_consistency": 8,
                    "technique_usage": 8,
                    "quality_level": 8,
                    "continuity": 8,
                    "structure": 8,
                    "boundary_control": 9,
                    "revision_effectiveness": 9,
                    "score": 84,
                    "overall_rating": "good",
                    "summary": "返修后完成本章目标，未侵占后续章纲。",
                    "issues": [],
                    "suggestions": ["可继续强化最后一段的情绪压力。"],
                    "strengths": ["边界控制清楚"],
                    "pass_review": True,
                    "needs_revision": False,
                }, ensure_ascii=False),
                "usage": {
                    "prompt_tokens": 80,
                    "completion_tokens": 40,
                    "model": "fake-revision-review",
                },
            }

        return {
            "content": (
                "沈砚在雪夜试炼中握紧玉牌，玉牌发烫让他意识到旧案线索仍在卷宗楼。"
                "他没有揭开旧案真相，只在玄门弟子的阻拦前稳住呼吸。"
                "结尾时，卷宗楼方向传来轻响，他决定进入卷宗楼之前先藏起玉牌。"
            ),
            "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 60,
                "model": "fake-revision-writer",
            },
        }

    def current_model_metadata(self):
        return {"model": "fake-revision-review", "model_profile_id": "profile_revision"}

    def current_usage_summary(self):
        return {"llm_prompt_tokens": 180, "llm_completion_tokens": 100}


def write_revision_workspace(project_root):
    outline_dir = project_root / "novel" / "outline"
    chapter_dir = project_root / "novel" / "chapters" / "drafts" / "book_1" / "volume_1"
    outline_dir.mkdir(parents=True)
    chapter_dir.mkdir(parents=True)

    outline = {
        "book_id": "book_1",
        "project_id": "proj_revision_quality",
        "book_title": "雪夜玄门",
        "genre": "玄幻",
        "target_word_count": 100000,
        "core_concept": "玉牌异动牵出旧案。",
        "world_view": "玄门以试炼与卷宗维护秩序。",
        "main_conflict": "沈砚追查旧案但不能牵连同伴。",
        "volumes": [{
            "volume_number": 1,
            "volume_title": "旧案初现",
            "main_goal": "发现旧案第一条线索",
            "character_growth": "沈砚从被动试炼转为主动追查",
            "external_conflict": "玄门弟子阻拦他接近卷宗楼",
            "resolution": "沈砚锁定卷宗楼但暂不揭开真相",
            "opening_node": {"chapter": 1, "description": "玉牌发烫"},
            "quarter_node": {"chapter": 1, "description": "试炼压力"},
            "midpoint_node": {"chapter": 1, "description": "卷宗楼线索"},
            "three_quarter_node": {"chapter": 2, "description": "遭遇阻拦"},
            "climax_node": {"chapter": 2, "description": "线索反转"},
            "chapters": [
                {
                    "chapter_number": 1,
                    "chapter_title": "雪夜玉牌",
                    "target_word_count": 3000,
                    "plot_goal": "沈砚发现玉牌在雪夜试炼中发烫，并锁定卷宗楼线索。",
                    "character_development": "沈砚从迟疑转向主动追查。",
                    "info_reveal": "玉牌与旧案线索有关。",
                    "conflict": "玄门试炼压力和卷宗楼禁令形成阻碍。",
                    "appeal_point": "主角识破玉牌异动的方向。",
                    "lead_to_next": "进入卷宗楼前遭遇阻拦。",
                    "core_goal": "玉牌发烫并锁定卷宗楼线索。",
                    "must_write": ["玉牌发烫", "卷宗楼线索"],
                    "allowed_progress": ["可暗示卷宗楼异常"],
                    "must_not_write": ["旧案真凶现身"],
                    "reserved_for_future": {"chapter_2": "遭遇阻拦"},
                    "stop_point": "决定进入卷宗楼之前先藏起玉牌",
                    "ending_hook": "卷宗楼方向传来轻响",
                },
                {
                    "chapter_number": 2,
                    "chapter_title": "卷宗疑云",
                    "target_word_count": 3000,
                    "plot_goal": "沈砚进入卷宗楼并遭遇阻拦。",
                    "core_goal": "遭遇阻拦",
                    "must_write": ["遭遇阻拦"],
                    "allowed_progress": ["可暴露阻拦者与旧案有关"],
                    "must_not_write": ["不得揭开旧案真相"],
                    "reserved_for_future": {"long_term": "旧案真相"},
                    "stop_point": "阻拦者认出玉牌",
                    "ending_hook": "卷宗被调换",
                },
            ],
        }],
    }
    (outline_dir / "book_1_outline.json").write_text(
        json.dumps(outline, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    chapter = {
        "chapter_id": "book_1_v1_c1",
        "book_id": "book_1",
        "volume_number": 1,
        "chapter_number": 1,
        "chapter_title": "雪夜玉牌",
        "content": "沈砚参加雪夜试炼，但这一版没有把卷宗楼线索写清楚。",
        "word_count": 27,
        "quality_score": 55,
        "review_status": "needs_revision",
        "review_comments": ["目标不完整"],
        "version": 1,
    }
    (chapter_dir / "chapter_1.json").write_text(
        json.dumps(chapter, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


@pytest.mark.asyncio
async def test_revision_agent_writes_post_revision_quality_review(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "PROJECT_BASE_PATH", str(tmp_path))
    project_id = "proj_revision_quality"
    project_root = tmp_path / "projects" / project_id
    write_revision_workspace(project_root)

    fake_llm = FakeRevisionLLM()
    agent = RevisionAgent(llm_client=fake_llm)
    response = await agent.run(AgentRequest(
        task_id="task_revision_quality",
        project_id=project_id,
        task_type="chapter_revision",
        parameters={
            "project_id": project_id,
            "book_id": "book_1",
            "volume_number": 1,
            "chapter_number": 1,
            "source_stage": "draft",
            "user_instruction": "补齐卷宗楼线索，但不要揭示旧案真相。",
            "max_iterations": 1,
            "auto_quality_review": True,
            "min_quality_score": 70,
        },
    ))

    assert response.status == "success"
    assert response.structured_output["quality_passed"] is True
    assert response.structured_output["quality_score"] == 84
    assert len(fake_llm.calls) == 2
    assert fake_llm.calls[1]["response_format"] == "json"

    chapter_path = project_root / "novel" / "chapters" / "drafts" / "book_1" / "volume_1" / "chapter_1.json"
    saved_chapter = json.loads(chapter_path.read_text(encoding="utf-8"))
    assert saved_chapter["review_status"] == "reviewed"
    assert saved_chapter["quality_score"] == 84
    assert saved_chapter["revision_quality"]["summary"] == "返修后完成本章目标，未侵占后续章纲。"
    assert saved_chapter["revision_history"][-1]["quality_review"]["score"] == 84

    review_path = Path(response.structured_output["review_path"])
    report = json.loads(review_path.read_text(encoding="utf-8"))
    assert report["revision_quality"]["score"] == 84
    assert report["revision_quality"]["suggestions"] == ["可继续强化最后一段的情绪压力。"]
