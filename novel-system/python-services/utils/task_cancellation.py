"""In-process cancellation registry for running agent requests."""

from __future__ import annotations

import asyncio
from typing import Dict, Optional


class TaskCancellationRegistry:
    """Track running asyncio tasks so Java can cancel long-running agents."""

    def __init__(self) -> None:
        self._tasks: Dict[str, asyncio.Task] = {}
        self._cancelled: set[str] = set()
        self._lock = asyncio.Lock()

    async def register(self, task_id: str, task: asyncio.Task) -> None:
        async with self._lock:
            self._tasks[task_id] = task
            self._cancelled.discard(task_id)

    async def unregister(self, task_id: str) -> None:
        async with self._lock:
            self._tasks.pop(task_id, None)

    async def cancel(self, task_id: str) -> Dict[str, object]:
        async with self._lock:
            self._cancelled.add(task_id)
            task: Optional[asyncio.Task] = self._tasks.get(task_id)
            if task and not task.done():
                task.cancel()
                return {"status": "cancelling", "task_id": task_id, "running": True}
            return {"status": "cancelled", "task_id": task_id, "running": False}

    async def is_cancelled(self, task_id: str) -> bool:
        async with self._lock:
            return task_id in self._cancelled


cancellation_registry = TaskCancellationRegistry()
