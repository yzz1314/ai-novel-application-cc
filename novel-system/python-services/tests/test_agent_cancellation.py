import asyncio
from datetime import datetime
import sys
import types

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.base import BaseAgent
from api.routes import agent_routes
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse


class SlowAgent(BaseAgent):
    def __init__(self):
        super().__init__("SlowAgent")
        self.supported_tasks = ["slow"]

    async def run(self, request: AgentRequest) -> AgentResponse:
        await asyncio.sleep(10)
        return AgentResponse(
            task_id=request.task_id,
            agent_name=self.agent_name,
            project_id=request.project_id,
            status="success",
            created_at=datetime.now(),
            finished_at=datetime.now(),
        )


@pytest.mark.asyncio
async def test_agent_route_cancels_running_task():
    original_agent = agent_routes.AGENT_REGISTRY.get("slow_test")
    agent_routes.AGENT_REGISTRY["slow_test"] = SlowAgent()
    request = AgentRequest(
        task_id="task_cancel_route",
        project_id="proj_cancel",
        task_type="slow",
    )

    try:
        running = asyncio.create_task(agent_routes.run_agent("slow_test", request))
        await asyncio.sleep(0.05)

        cancel_response = await agent_routes.cancel_task(request.task_id)
        response = await asyncio.wait_for(running, timeout=1)
    finally:
        if original_agent is not None:
            agent_routes.AGENT_REGISTRY["slow_test"] = original_agent
        else:
            agent_routes.AGENT_REGISTRY.pop("slow_test", None)

    assert cancel_response["running"] is True
    assert cancel_response["status"] == "cancelling"
    assert response.status == "cancelled"
    assert response.structured_output["cancelled"] is True
    assert response.warnings[0]["code"] == "TASK_CANCELLED"
