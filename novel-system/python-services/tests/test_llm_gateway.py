import json
import sys
import types
from types import SimpleNamespace

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

import llm.client as llm_client_module
from config import settings
from llm.client import LLMClient


class FakeResponse:
    def __init__(self, content="fallback ok", prompt_tokens=120, completion_tokens=40):
        self.choices = [
            SimpleNamespace(message=SimpleNamespace(content=content))
        ]
        self.usage = SimpleNamespace(
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )


@pytest.mark.asyncio
async def test_llm_gateway_fallback_cache_and_cost_metrics(tmp_path, monkeypatch):
    config_dir = tmp_path / "config"
    config_dir.mkdir(parents=True)
    (config_dir / "model_profiles.json").write_text(
        json.dumps({
            "defaultProfileId": "gateway_profile",
            "profiles": [
                {
                    "profileId": "gateway_profile",
                    "profileName": "Gateway Profile",
                    "mainModel": {
                        "provider": "custom",
                        "model": "primary-model",
                        "endpoint": "https://example.invalid/v1",
                        "apiKey": "primary-key",
                        "mock": False,
                        "cacheEnabled": True,
                        "cacheTtlSeconds": 3600,
                        "inputCostPer1K": 0.01,
                        "outputCostPer1K": 0.03,
                    },
                    "fallbackModels": [
                        {
                            "provider": "custom",
                            "model": "fallback-model",
                            "endpoint": "https://example.invalid/v1",
                            "apiKey": "fallback-key",
                            "mock": False,
                            "inputCostPer1K": 0.002,
                            "outputCostPer1K": 0.004,
                        }
                    ],
                }
            ],
        }, ensure_ascii=False),
        encoding="utf-8",
    )

    calls = []

    async def fake_acompletion(**kwargs):
        calls.append(kwargs["model"])
        if kwargs["model"] == "primary-model":
            raise RuntimeError("primary rate limit")
        return FakeResponse()

    monkeypatch.setattr(llm_client_module, "acompletion", fake_acompletion)
    original_base_path = settings.PROJECT_BASE_PATH
    original_mock = settings.MOCK_LLM
    settings.PROJECT_BASE_PATH = str(tmp_path)
    settings.MOCK_LLM = False
    try:
        client = LLMClient()
        async with client.profile_context("gateway_profile", "chapter_writing"):
            first = await client.generate("请生成一段章节正文")
            second = await client.generate("请生成一段章节正文")
            metadata = client.current_model_metadata()
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
        settings.MOCK_LLM = original_mock

    assert calls == ["primary-model", "fallback-model"]
    assert first["content"] == "fallback ok"
    assert first["usage"]["fallback_used"] is True
    assert first["usage"]["fallback_attempts"] == 1
    assert first["usage"]["fallback_errors"][0]["model"] == "primary-model"
    assert first["usage"]["model"] == "fallback-model"
    assert first["usage"]["cost_estimated"] is True
    assert first["usage"]["estimated_cost_usd"] == 0.0004

    assert second["usage"]["cache_hit"] is True
    assert second["usage"]["fallback_used"] is True
    assert second["usage"]["cache_key"] == first["usage"]["cache_key"]
    assert metadata["model"] == "fallback-model"
    assert metadata["cache_hit"] is True
    assert metadata["fallback_used"] is True


@pytest.mark.asyncio
async def test_llm_gateway_rate_limit_interval(monkeypatch):
    client = LLMClient(model_config={"model": "mock-local", "mock": True})
    config = {"model": "mock-local", "min_interval_ms": 20}
    assert client._min_interval_seconds(config) == 0.02
