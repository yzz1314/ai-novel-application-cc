import json
import sys
import types
from pathlib import Path

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

PYTHON_ROOT = Path(__file__).resolve().parents[1]
if str(PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(PYTHON_ROOT))

from agents.outline_review_agent import OutlineReviewAgent
from config import settings
from schemas.agent_request import AgentRequest


class FakeSemanticLLM:
    def __init__(self):
        self.prompt = ""

    async def generate_with_retry(self, prompt, response_format=None, **kwargs):
        self.prompt = prompt
        assert response_format == "json"
        assert kwargs["task_type"] == "outline_review"
        return {
            "content": json.dumps({
                "score": 82,
                "status": "passed",
                "summary": "主线清楚，章节边界可支持正文创作。",
                "dimensions": {
                    "soul_alignment": 8,
                    "plot_causality": 8,
                    "character_arc": 8,
                    "worldbuilding_constraints": 8,
                    "chapter_boundary_control": 9,
                    "reader_hook": 8,
                },
                "strengths": ["章节停止点明确"],
                "issues": [{
                    "severity": "warning",
                    "code": "weak_volume_hook",
                    "message": "卷末反转还可以更强。",
                    "path": "volumes[0]",
                }],
                "suggestions": ["强化卷末代价。"],
                "pass_review": True,
            }, ensure_ascii=False),
            "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 60,
                "model": "fake-semantic-model",
                "model_profile_id": "profile_quality",
                "model_role": "mainModel",
            },
        }

    def current_model_metadata(self):
        return {
            "model_profile_id": "profile_quality",
            "model_role": "mainModel",
            "model": "fake-semantic-model",
            "provider": "mock",
        }

    def current_usage_summary(self):
        return {
            "llm_prompt_tokens": 120,
            "llm_completion_tokens": 60,
            "llm_total_tokens": 180,
            "llm_models": ["fake-semantic-model"],
        }


def write_reviewable_outline(project_root):
    outline_dir = project_root / "novel" / "outline"
    soul_dir = project_root / "novel" / "soul"
    outline_dir.mkdir(parents=True)
    soul_dir.mkdir(parents=True)
    (soul_dir / "project_soul.md").write_text(
        (
            "核心：主角在玄门试炼中追查旧案，以玉牌异动为主线牵引。"
            "不可提前揭示旧案真相，不可让人物目标脱离世界规则。"
            "人物：沈砚冷静坚韧，必须通过选择承担代价。"
            "世界：玄门、卷宗楼、玉牌感应都有明确限制。"
        ) * 4,
        encoding="utf-8",
    )
    outline = {
        "book_id": "book_1",
        "book_title": "雪夜玄门",
        "genre": "玄幻",
        "core_concept": "玉牌异动牵出旧案真相。",
        "world_view": "玄门以卷宗和试炼维护秩序，玉牌感应受规则限制。",
        "main_conflict": "主角必须在追查旧案和保护同伴之间做选择。",
        "characters": [{"name": "沈砚", "role": "主角"}],
        "volumes": [{
            "volume_number": 1,
            "volume_title": "旧案初现",
            "main_conflict": "主角进入玄门试炼并发现卷宗异常。",
            "chapters": [
                {
                    "chapter_number": 1,
                    "chapter_title": "雪夜玉牌",
                    "plot_goal": "沈砚发现玉牌在雪夜试炼中异常发烫，并锁定卷宗楼线索。",
                    "target_word_count": 3000,
                    "core_goal": "完成玉牌异动与旧案线索的首次绑定。",
                    "must_write": ["玉牌发烫", "玄门试炼压力", "卷宗楼线索"],
                    "allowed_progress": ["可暗示卷宗楼藏有旧案，但不解释真相"],
                    "must_not_write": ["不得提前揭示旧案真凶"],
                    "reserved_for_future": {"chapter_2": "进入卷宗楼并遭遇阻拦"},
                    "stop_point": "停在沈砚决定进入卷宗楼之前。",
                    "ending_hook": "玉牌在卷宗楼方向再次发烫。",
                },
                {
                    "chapter_number": 2,
                    "chapter_title": "卷宗疑云",
                    "plot_goal": "沈砚进入卷宗楼，发现旧案卷宗被人调换。",
                    "target_word_count": 3000,
                    "core_goal": "完成卷宗异常发现并引出阻拦者。",
                    "must_write": ["卷宗被调换", "阻拦者出现"],
                    "allowed_progress": ["可暴露阻拦者与旧案有关"],
                    "must_not_write": ["不得提前解决旧案主谜题"],
                    "reserved_for_future": {"long_term_suspense": "旧案真相留到后续卷展开"},
                    "stop_point": "停在阻拦者露出身份线索时。",
                    "ending_hook": "阻拦者认出玉牌来源。",
                },
            ],
        }],
    }
    (outline_dir / "book_1_outline.json").write_text(
        json.dumps(outline, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


@pytest.mark.asyncio
async def test_outline_review_writes_llm_semantic_quality(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "PROJECT_BASE_PATH", str(tmp_path))
    project_id = "proj_outline_semantic"
    project_root = tmp_path / "projects" / project_id
    write_reviewable_outline(project_root)

    fake_llm = FakeSemanticLLM()
    agent = OutlineReviewAgent(llm_client=fake_llm)
    response = await agent.run(AgentRequest(
        task_id="task_outline_semantic",
        project_id=project_id,
        task_type="outline_review",
        model_profile_id="profile_quality",
        input_refs={"book_id": "book_1"},
        parameters={"semanticQuality": True},
    ))

    assert response.status == "success"
    report = response.structured_output
    assert "Project Soul" in fake_llm.prompt
    assert "LLM" in fake_llm.prompt
    assert report["semantic_quality"]["attempted"] is True
    assert report["semantic_quality"]["score"] == 82
    assert report["semantic_quality"]["model_gateway"]["model"] == "fake-semantic-model"
    assert report["score"] == 94
    assert report["rule_score"] == 100
    assert any(item["code"] == "weak_volume_hook" for item in report["findings"])
    assert response.metrics["llm_prompt_tokens"] == 120

    report_path = project_root / report["report_path"]
    saved_report = json.loads(report_path.read_text(encoding="utf-8"))
    assert saved_report["semantic_quality"]["suggestions"] == ["强化卷末代价。"]
