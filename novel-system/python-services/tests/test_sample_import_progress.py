import json
import sys
import types

from config import settings
from schemas.agent_request import AgentRequest

litellm_stub = types.ModuleType("litellm")

async def _unused_acompletion(*_args, **_kwargs):
    raise RuntimeError("litellm stub should not be called by sample import tests")

litellm_stub.acompletion = _unused_acompletion
sys.modules.setdefault("litellm", litellm_stub)

from agents.sample_import_agent import SampleImportAgent


async def test_sample_import_writes_progress_checkpoints(tmp_path):
    original_base_path = settings.PROJECT_BASE_PATH
    project_id = "project_import_progress"
    sample_id = "sample_progress"
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        raw_dir = tmp_path / "projects" / project_id / "samples" / "raw"
        raw_dir.mkdir(parents=True)
        raw_file = raw_dir / f"{sample_id}.txt"
        raw_file.write_text(
            "\n".join([
                "Progress Sample",
                "第一章 起点",
                "主角踏入旧城，发现一枚会发烫的铜钥匙。",
                "第二章 余波",
                "铜钥匙在夜色里指向城外的废塔。",
            ]),
            encoding="utf-8"
        )

        response = await SampleImportAgent().run(AgentRequest(
            task_id="task_sample_import_progress",
            project_id=project_id,
            task_type="sample_import",
            input_refs={
                "sample_id": sample_id,
                "file_path": str(raw_file),
            },
            parameters={"sample_id": sample_id},
        ))

        latest_path = (
            tmp_path / "projects" / project_id / "checkpoints" /
            "task_sample_import_progress_sample_import_latest.json"
        )
        payload = json.loads(latest_path.read_text(encoding="utf-8"))
        state = payload["state"]

        assert response.status == "success"
        assert response.checkpoint_ref
        assert response.structured_output["progress_percent"] == 100
        assert response.structured_output["checkpoint_ref"] == response.checkpoint_ref
        assert state["stage"] == "done"
        assert state["processed_steps"] == state["total_steps"]
        assert state["progress_percent"] == 100
        assert state["sample_id"] == sample_id
        assert (tmp_path / "projects" / project_id / "samples" / "manifests" / f"{sample_id}_manifest.json").exists()
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
