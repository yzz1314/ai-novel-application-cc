import json
import sys
import types

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.book_summary_agent import BookSummaryAgent
from config import settings
from schemas.agent_request import AgentRequest


class CapturingLLM:
    def __init__(self):
        self.calls = []

    async def generate_with_retry(self, **kwargs):
        self.calls.append(kwargs)
        return {
            "content": "# 测试报告\n\n真实模型报告。",
            "usage": {"prompt_tokens": 100, "completion_tokens": 20},
        }

    def current_model_metadata(self):
        return {"mock": False, "model": "gpt-5.5", "provider": "custom"}

    def current_usage_summary(self):
        return {}


class TimeoutLLM(CapturingLLM):
    async def generate_with_retry(self, **kwargs):
        self.calls.append(kwargs)
        raise Exception("LLM生成失败: Request timed out.")


@pytest.mark.asyncio
async def test_book_summary_uses_compact_prompt_and_bounded_output(tmp_path):
    project_id = "proj_book_summary"
    sample_id = "sample_a"
    analysis_dir = tmp_path / "projects" / project_id / "analysis" / "per_chunk" / sample_id
    analysis_dir.mkdir(parents=True)
    (analysis_dir / "summary.json").write_text(
        json.dumps({
            "title": "测试书",
            "total_chars": 12345,
            "total_chapters": 12,
            "technique_stats": {},
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    for index in range(20):
        (analysis_dir / f"chunk_{index:04d}_analysis.json").write_text(
            json.dumps({
                "chunk_id": f"chunk_{index:04d}",
                "analysis": {
                    "summary": f"第{index}块摘要",
                    "plot_function": "推进矛盾",
                },
            }, ensure_ascii=False),
            encoding="utf-8",
        )

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    llm = CapturingLLM()
    try:
        response = await BookSummaryAgent(llm_client=llm).run(AgentRequest(
            task_id="task_book_summary",
            project_id=project_id,
            task_type="book_summary",
            input_refs={"sample_id": sample_id},
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert response.status == "success"
    assert llm.calls[0]["max_tokens"] <= 2000
    assert "第0块摘要" in llm.calls[0]["prompt"]
    assert "N/A" not in llm.calls[0]["prompt"]


@pytest.mark.asyncio
async def test_book_summary_writes_partial_report_when_llm_times_out(tmp_path):
    project_id = "proj_book_summary_timeout"
    sample_id = "sample_timeout"
    analysis_dir = tmp_path / "projects" / project_id / "analysis" / "per_chunk" / sample_id
    analysis_dir.mkdir(parents=True)
    (analysis_dir / "summary.json").write_text(
        json.dumps({
            "title": "超时测试书",
            "total_chars": 20000,
            "total_chapters": 20,
            "technique_stats": {"hook": 3},
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    for index in range(3):
        (analysis_dir / f"chunk_{index:04d}_analysis.json").write_text(
            json.dumps({
                "chunk_id": f"chunk_{index:04d}",
                "analysis": {
                    "summary": f"第{index}块摘要",
                    "plot_function": "推进矛盾",
                },
            }, ensure_ascii=False),
            encoding="utf-8",
        )

    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    llm = TimeoutLLM()
    try:
        response = await BookSummaryAgent(llm_client=llm).run(AgentRequest(
            task_id="task_book_summary_timeout",
            project_id=project_id,
            task_type="book_summary",
            input_refs={"sample_id": sample_id},
        ))
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    report_file = (
        tmp_path / "projects" / project_id / "analysis" / "per_book" / f"{sample_id}_report.md"
    )
    report_content = report_file.read_text(encoding="utf-8")
    assert response.status == "partial"
    assert response.output_refs == [f"analysis/per_book/{sample_id}_report.md"]
    assert response.warnings[0]["code"] == "BOOK_SUMMARY_LLM_TIMEOUT_FALLBACK"
    assert llm.calls[0]["max_retries"] == 1
    assert llm.calls[0]["num_retries"] == 0
    assert llm.calls[0]["sdk_max_retries"] == 0
    assert llm.calls[0]["timeout"] == 60
    assert "模型生成超时" in report_content
    assert "第0块摘要" in report_content
