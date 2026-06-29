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
from text_processing import Chunker


def test_chunk_by_chapters_assigns_globally_unique_ids():
    text = ("第一章 起点\n" + "一段测试文本。" * 420 + "\n"
            "第二章 余波\n" + "另一段测试文本。" * 420)
    first_end = text.index("第二章")
    chapters = [
        {
            "chapter_index": 1,
            "title": "第一章 起点",
            "start_offset": 0,
            "end_offset": first_end,
        },
        {
            "chapter_index": 2,
            "title": "第二章 余波",
            "start_offset": first_end,
            "end_offset": len(text),
        },
    ]

    chunks = Chunker(max_chunk_size=900, overlap=80).chunk_by_chapters(text, chapters)
    chunk_ids = [chunk["id"] for chunk in chunks]

    assert len(chunks) > 2
    assert len(chunk_ids) == len(set(chunk_ids))
    assert chunk_ids == [f"chunk_{index:06d}" for index in range(len(chunks))]


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
        manifest_path = tmp_path / "projects" / project_id / "samples" / "manifests" / f"{sample_id}_manifest.json"
        chunks_dir = tmp_path / "projects" / project_id / "samples" / "chunks" / sample_id
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        chunk_files = list(chunks_dir.glob("*.json"))

        assert manifest_path.exists()
        assert len(chunk_files) == manifest["total_chunks"]
    finally:
        settings.PROJECT_BASE_PATH = original_base_path
