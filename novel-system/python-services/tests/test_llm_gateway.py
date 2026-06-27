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
    project_id = "project_gateway"
    task_id = "task_gateway"
    settings.PROJECT_BASE_PATH = str(tmp_path)
    settings.MOCK_LLM = False
    try:
        client = LLMClient()
        async with client.profile_context(
                "gateway_profile",
                "chapter_writing",
                project_id=project_id,
                task_id=task_id):
            first = await client.generate("请生成一段章节正文")
            second = await client.generate("请生成一段章节正文")
            metadata = client.current_model_metadata()
            usage_summary = client.current_usage_summary()
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

    ledger_path = tmp_path / "projects" / project_id / "logs" / "llm_usage" / f"{task_id}.jsonl"
    assert ledger_path.exists()
    ledger_lines = [
        json.loads(line)
        for line in ledger_path.read_text(encoding="utf-8").splitlines()
    ]
    assert len(ledger_lines) == 2
    assert ledger_lines[0]["cache_hit"] is False
    assert ledger_lines[0]["fallback_used"] is True
    assert ledger_lines[0]["fallback_attempts"] == 1
    assert ledger_lines[0]["model"] == "fallback-model"
    assert ledger_lines[0]["project_id"] == project_id
    assert ledger_lines[0]["task_id"] == task_id
    assert ledger_lines[1]["cache_hit"] is True
    assert ledger_lines[1]["fallback_used"] is True

    assert usage_summary["llm_call_count"] == 2
    assert usage_summary["llm_cache_hits"] == 1
    assert usage_summary["llm_fallback_count"] == 2
    assert usage_summary["llm_fallback_attempts"] == 2
    assert usage_summary["llm_prompt_tokens"] == 240
    assert usage_summary["llm_completion_tokens"] == 80
    assert usage_summary["llm_total_tokens"] == 320
    assert usage_summary["llm_estimated_cost_usd"] == 0.0008
    assert usage_summary["llm_models"] == ["fallback-model"]
    assert usage_summary["llm_model_roles"] == ["fallbackModel[0]"]
    assert usage_summary["llm_usage_log_path"] == "logs/llm_usage/task_gateway.jsonl"


@pytest.mark.asyncio
async def test_llm_gateway_rate_limit_interval(monkeypatch):
    client = LLMClient(model_config={"model": "mock-local", "mock": True})
    config = {"model": "mock-local", "model_profile_id": "profile_rate", "min_interval_ms": 20}
    assert client._min_interval_seconds(config) == 0.02

    clock = {"value": 100.0}
    sleeps = []

    def fake_monotonic():
        return clock["value"]

    async def fake_sleep(seconds):
        sleeps.append(seconds)
        clock["value"] += seconds

    monkeypatch.setattr(llm_client_module.time, "monotonic", fake_monotonic)
    monkeypatch.setattr(llm_client_module.asyncio, "sleep", fake_sleep)

    await client._apply_rate_limit(config)
    await client._apply_rate_limit(config)

    assert sleeps == [pytest.approx(0.02)]
    assert client.rate_limit_state["profile_rate:mock-local"] == pytest.approx(100.04)


@pytest.mark.asyncio
async def test_llm_gateway_resolves_runtime_secret_reference(tmp_path, monkeypatch):
    config_dir = tmp_path / "config"
    config_dir.mkdir(parents=True)
    secret_ref = "secret://model-profiles/secret_profile/mainModel/apiKey"
    (config_dir / "model_profiles.json").write_text(
        json.dumps({
            "defaultProfileId": "secret_profile",
            "secrets": "runtime-ref",
            "secretsFile": "config/model_profile_secrets.json",
            "profiles": [
                {
                    "profileId": "secret_profile",
                    "mainModel": {
                        "provider": "custom",
                        "model": "secret-model",
                        "endpoint": "https://example.invalid/v1",
                        "apiKeyRef": secret_ref,
                        "mock": False,
                    },
                }
            ],
        }, ensure_ascii=False),
        encoding="utf-8",
    )
    (config_dir / "model_profile_secrets.json").write_text(
        json.dumps({
            "version": "1.0.0",
            "secrets": {
                secret_ref: "runtime-secret-key",
            },
        }, ensure_ascii=False),
        encoding="utf-8",
    )

    call_kwargs = {}

    async def fake_acompletion(**kwargs):
        call_kwargs.update(kwargs)
        return FakeResponse(content="secret ok")

    monkeypatch.setattr(llm_client_module, "acompletion", fake_acompletion)
    original_base_path = settings.PROJECT_BASE_PATH
    original_mock = settings.MOCK_LLM
    settings.PROJECT_BASE_PATH = str(tmp_path)
    settings.MOCK_LLM = False
    try:
        client = LLMClient()
        async with client.profile_context("secret_profile", "chapter_writing"):
            response = await client.generate("secret ref smoke")
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
        settings.MOCK_LLM = original_mock

    assert response["content"] == "secret ok"
    assert call_kwargs["model"] == "secret-model"
    assert call_kwargs["api_key"] == "runtime-secret-key"
    assert "runtime-secret-key" not in (config_dir / "model_profiles.json").read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_llm_gateway_embedding_and_rerank_local_fallback(tmp_path):
    config_dir = tmp_path / "config"
    config_dir.mkdir(parents=True)
    (config_dir / "model_profiles.json").write_text(
        json.dumps({
            "defaultProfileId": "retrieval_profile",
            "profiles": [
                {
                    "profileId": "retrieval_profile",
                    "mainModel": {"provider": "mock", "model": "mock-main", "mock": True},
                    "embeddingModel": {"provider": "mock", "model": "mock-embedding", "mock": True},
                    "rerankModel": {"provider": "mock", "model": "mock-rerank", "mock": True},
                }
            ],
        }, ensure_ascii=False),
        encoding="utf-8",
    )

    original_base_path = settings.PROJECT_BASE_PATH
    original_mock = settings.MOCK_LLM
    settings.PROJECT_BASE_PATH = str(tmp_path)
    settings.MOCK_LLM = False
    try:
        client = LLMClient()
        async with client.profile_context("retrieval_profile", "retrieval_index"):
            embedding = await client.embed_texts(["jade token", "hidden archive"])
            rerank = await client.rerank(
                "jade token archive",
                [
                    {"doc_id": "a", "title": "Archive", "text": "hidden archive clue"},
                    {"doc_id": "b", "title": "Token", "text": "jade token heats"},
                ],
                top_k=2,
            )
            usage_summary = client.current_usage_summary()
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
        settings.MOCK_LLM = original_mock

    assert len(embedding["embeddings"]) == 2
    assert len(embedding["embeddings"][0]) == 96
    assert embedding["model_gateway"]["operation"] == "embedding"
    assert embedding["model_gateway"]["local_fallback"] is True
    assert embedding["usage"]["model_role"] == "embeddingModel"

    assert [item["doc_id"] for item in rerank["results"]] == ["b", "a"]
    assert rerank["model_gateway"]["operation"] == "rerank"
    assert rerank["usage"]["model_role"] == "rerankModel"
    assert usage_summary["llm_call_count"] == 2
    assert set(usage_summary["llm_model_roles"]) == {"embeddingModel", "rerankModel"}
