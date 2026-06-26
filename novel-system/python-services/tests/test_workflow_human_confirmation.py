from datetime import datetime
import sys
import types

import pytest

sys.modules.setdefault("litellm", types.SimpleNamespace(acompletion=None))

from agents.base import BaseAgent
from config import settings
from orchestrator.workflow_engine import WorkflowEngine
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse


class EchoAgent(BaseAgent):
    def __init__(self):
        super().__init__("EchoAgent")
        self.supported_tasks = ["echo"]

    async def run(self, request: AgentRequest) -> AgentResponse:
        return AgentResponse(
            task_id=request.task_id,
            agent_name=self.agent_name,
            project_id=request.project_id,
            status="success",
            output_refs=["echo/result.json"],
            structured_output={
                "ran": True,
                "task_id": request.task_id,
                "parameters": request.parameters,
            },
            created_at=datetime.now(),
            finished_at=datetime.now(),
        )


def gated_workflow():
    return {
        "workflow_id": "test_gate",
        "workflow_name": "Test Gate",
        "nodes": [
            {
                "id": "review_gate",
                "type": "human_confirmation",
                "prompt": "Approve the next node?",
            },
            {
                "id": "after_gate",
                "type": "agent",
                "agent": "echo",
                "task_type": "echo",
                "parameters": {"approved_context": "${input.context}"},
            },
        ],
    }


def parallel_workflow():
    return {
        "workflow_id": "test_parallel",
        "workflow_name": "Test Parallel",
        "nodes": [
            {
                "id": "fanout",
                "type": "parallel",
                "branches": [
                    {
                        "id": "branch_a",
                        "agent": "echo",
                        "task_type": "echo",
                        "parameters": {"sample_id": "${input.sample_id_a}"},
                    },
                    {
                        "id": "branch_b",
                        "agent": "echo",
                        "task_type": "echo",
                        "parameters": {"sample_id": "${input.sample_id_b}"},
                    },
                ],
            },
            {
                "id": "after_parallel",
                "type": "agent",
                "agent": "echo",
                "task_type": "echo",
                "condition": {"source": "outputs.fanout.status", "equals": "success"},
                "parameters": {
                    "branch_count": "${outputs.fanout.branch_count}",
                    "branch_a_sample": "${outputs.branch_a.structured_output.parameters.sample_id}",
                    "branch_b_task": "${outputs.branch_b.task_id}",
                },
            },
        ],
    }


def conditional_workflow():
    return {
        "workflow_id": "test_condition",
        "workflow_name": "Test Condition",
        "nodes": [
            {
                "id": "score_source",
                "type": "agent",
                "agent": "echo",
                "task_type": "echo",
                "parameters": {
                    "score": "${input.score}",
                    "tags": "${input.tags}",
                },
            },
            {
                "id": "quality_gate",
                "type": "agent",
                "agent": "echo",
                "task_type": "echo",
                "condition": {
                    "all_of": [
                        {"source": "outputs.score_source.structured_output.parameters.score", "op": "gte", "value": 80},
                        {"source": "outputs.score_source.structured_output.parameters.tags", "op": "contains", "value": "approved"},
                    ]
                },
                "parameters": {"gate": "opened"},
            },
            {
                "id": "fallback_gate",
                "type": "agent",
                "agent": "echo",
                "task_type": "echo",
                "condition": {"not": {"source": "outputs.score_source.structured_output.parameters.score", "op": "gte", "value": 80}},
                "parameters": {"gate": "fallback"},
            },
        ],
    }


@pytest.mark.asyncio
async def test_workflow_pauses_until_human_confirmation(tmp_path):
    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        engine = WorkflowEngine(lambda: {"echo": EchoAgent()})
        response = await engine.execute(
            AgentRequest(
                task_id="task_wait",
                project_id="proj_workflow_gate",
                task_type="workflow",
                input_refs={"context": "chapter-1"},
            ),
            gated_workflow(),
        )
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert response.status == "partial"
    assert response.checkpoint_ref
    assert response.structured_output["status"] == "waiting_for_human"
    assert response.structured_output["waiting_for_human"]["node_id"] == "review_gate"
    assert response.structured_output["next_node_index"] == 0
    assert "review_gate" not in response.structured_output["node_results"]


@pytest.mark.asyncio
async def test_workflow_resume_approval_continues_after_gate(tmp_path):
    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        engine = WorkflowEngine(lambda: {"echo": EchoAgent()})
        paused = await engine.execute(
            AgentRequest(
                task_id="task_approve",
                project_id="proj_workflow_gate",
                task_type="workflow",
                input_refs={"context": "chapter-1"},
            ),
            gated_workflow(),
        )

        resumed = await engine.execute(
            AgentRequest(
                task_id="task_approve_resume",
                project_id="proj_workflow_gate",
                task_type="workflow",
                input_refs={"context": "chapter-1"},
                resume_from_checkpoint=paused.checkpoint_ref,
                parameters={
                    "resume_input": {
                        "human_confirmations": {
                            "review_gate": {
                                "approved": True,
                                "reviewer": "tester",
                                "note": "looks good",
                            }
                        }
                    }
                },
            ),
            gated_workflow(),
        )
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert resumed.status == "success"
    assert resumed.structured_output["status"] == "success"
    assert resumed.structured_output["waiting_for_human"] is None
    assert resumed.structured_output["node_results"]["review_gate"]["decision"]["approved"] is True
    assert resumed.structured_output["node_results"]["after_gate"]["status"] == "success"
    assert resumed.metrics["workflow_nodes_completed"] == 2


@pytest.mark.asyncio
async def test_workflow_resume_rejection_cancels_workflow(tmp_path):
    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        engine = WorkflowEngine(lambda: {"echo": EchoAgent()})
        paused = await engine.execute(
            AgentRequest(
                task_id="task_reject",
                project_id="proj_workflow_gate",
                task_type="workflow",
                input_refs={"context": "chapter-1"},
            ),
            gated_workflow(),
        )

        rejected = await engine.execute(
            AgentRequest(
                task_id="task_reject_resume",
                project_id="proj_workflow_gate",
                task_type="workflow",
                input_refs={"context": "chapter-1"},
                resume_from_checkpoint=paused.checkpoint_ref,
                parameters={
                    "resume_input": {
                        "human_confirmations": {
                            "review_gate": {
                                "approved": False,
                                "reviewer": "tester",
                                "note": "needs edits",
                            }
                        }
                    }
                },
            ),
            gated_workflow(),
        )
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    assert rejected.status == "cancelled"
    assert rejected.structured_output["status"] == "cancelled"
    assert rejected.structured_output["waiting_for_human"] is None
    assert rejected.structured_output["node_results"]["review_gate"]["status"] == "cancelled"
    assert "after_gate" not in rejected.structured_output["node_results"]


@pytest.mark.asyncio
async def test_workflow_parallel_branches_feed_downstream_node(tmp_path):
    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        engine = WorkflowEngine(lambda: {"echo": EchoAgent()})
        response = await engine.execute(
            AgentRequest(
                task_id="task_parallel",
                project_id="proj_workflow_parallel",
                task_type="workflow",
                input_refs={"sample_id_a": "sample_a", "sample_id_b": "sample_b"},
            ),
            parallel_workflow(),
        )
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    results = response.structured_output["node_results"]
    assert response.status == "success"
    assert results["fanout"]["status"] == "success"
    assert results["fanout"]["branch_count"] == 2
    assert results["fanout"]["branch_statuses"] == {"branch_a": "success", "branch_b": "success"}
    assert results["branch_a"]["structured_output"]["parameters"]["sample_id"] == "sample_a"
    assert results["branch_b"]["structured_output"]["parameters"]["sample_id"] == "sample_b"
    assert results["after_parallel"]["structured_output"]["parameters"]["branch_count"] == 2
    assert results["after_parallel"]["structured_output"]["parameters"]["branch_a_sample"] == "sample_a"
    assert results["after_parallel"]["structured_output"]["parameters"]["branch_b_task"] == "task_parallel_branch_b"


@pytest.mark.asyncio
async def test_workflow_complex_conditions_run_and_skip_nodes(tmp_path):
    original_base_path = settings.PROJECT_BASE_PATH
    settings.PROJECT_BASE_PATH = str(tmp_path)
    try:
        engine = WorkflowEngine(lambda: {"echo": EchoAgent()})
        response = await engine.execute(
            AgentRequest(
                task_id="task_condition",
                project_id="proj_workflow_condition",
                task_type="workflow",
                input_refs={"score": 86, "tags": ["draft", "approved"]},
            ),
            conditional_workflow(),
        )
    finally:
        settings.PROJECT_BASE_PATH = original_base_path

    results = response.structured_output["node_results"]
    assert response.status == "success"
    assert results["quality_gate"]["status"] == "success"
    assert results["quality_gate"]["structured_output"]["parameters"]["gate"] == "opened"
    assert results["fallback_gate"]["status"] == "skipped"
    assert results["fallback_gate"]["reason"] == "condition_not_met"
    assert response.metrics["workflow_nodes_completed"] == 3
