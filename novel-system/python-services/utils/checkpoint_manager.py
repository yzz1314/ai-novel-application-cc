"""File-based checkpoint persistence for project tasks."""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict

from config import settings


class CheckpointManager:
    """Persist and load task state under a project's checkpoints folder."""

    def project_root(self, project_id: str) -> Path:
        return Path(settings.PROJECT_BASE_PATH) / "projects" / project_id

    def checkpoint_dir(self, project_id: str) -> Path:
        path = self.project_root(project_id) / "checkpoints"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def save(self, project_id: str, task_id: str, checkpoint_scope: str, state: Dict[str, Any]) -> str:
        checkpoint_dir = self.checkpoint_dir(project_id)
        timestamp = datetime.now().strftime("%Y%m%d%H%M%S%f")
        safe_scope = "".join(ch if ch.isalnum() or ch in ("_", "-") else "_" for ch in checkpoint_scope)
        checkpoint_id = f"{task_id}_{safe_scope}_{timestamp}"
        payload = {
            "checkpoint_id": checkpoint_id,
            "task_id": task_id,
            "checkpoint_scope": checkpoint_scope,
            "project_id": project_id,
            "saved_at": datetime.now().isoformat(),
            "state": state,
        }

        text = json.dumps(payload, ensure_ascii=False, indent=2, default=str)
        checkpoint_path = checkpoint_dir / f"{checkpoint_id}.json"
        latest_path = checkpoint_dir / f"{task_id}_{safe_scope}_latest.json"
        checkpoint_path.write_text(text, encoding="utf-8")
        latest_path.write_text(text, encoding="utf-8")
        return str(checkpoint_path)

    def load(self, project_id: str, checkpoint_ref: str) -> Dict[str, Any]:
        if not checkpoint_ref:
            raise ValueError("checkpoint_ref is required")

        checkpoint_path = Path(checkpoint_ref)
        if not checkpoint_path.is_absolute():
            checkpoint_path = self.project_root(project_id) / checkpoint_path

        resolved = checkpoint_path.resolve()
        project_root = self.project_root(project_id).resolve()
        if not str(resolved).startswith(str(project_root)):
            raise ValueError("checkpoint_ref must stay inside the project workspace")
        if not resolved.exists():
            raise FileNotFoundError(f"Checkpoint not found: {checkpoint_ref}")

        payload = json.loads(resolved.read_text(encoding="utf-8"))
        return payload["state"]
