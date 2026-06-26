"""Local workflow engine with file checkpoints.

The full architecture calls for LangGraph, checkpointing, conditional nodes,
parallel fan-out and human confirmation. This module provides a local
implementation that uses the existing Agent registry, so workflows can run
before a heavier orchestration runtime is introduced.

Supported node types:
- ``agent``              run a registered agent
- ``human_confirmation`` pause until a human approves (resume via checkpoint)
- ``parallel``           run several branch nodes concurrently via asyncio

Conditions (per node, optional ``condition`` field):
- ``"previous_success"``                last executed node succeeded
- ``"always"`` / ``"never"``            constant
- ``{"source": x, "equals": y}``        legacy equality check
- ``{"source": x, "op": "gt", "value": y}`` operator comparison
  ops: eq, ne, gt, gte, lt, lte, in, not_in, contains, exists, truthy
- ``{"all_of": [cond, ...]}``           boolean AND
- ``{"any_of": [cond, ...]}``           boolean OR
- ``{"not": cond}``                     boolean NOT
"""

from __future__ import annotations

import asyncio
import re
from copy import deepcopy
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from utils.checkpoint_manager import CheckpointManager


EXPR_PATTERN = re.compile(r"^\$\{([^}]+)}$")


class WorkflowEngine:
    """Execute a workflow definition against the registered Agents."""

    def __init__(
        self,
        registry_provider: Callable[[], Dict[str, BaseAgent]],
        llm_client: Any = None,
        checkpoint_manager: Optional[CheckpointManager] = None,
    ):
        self.registry_provider = registry_provider
        self.llm_client = llm_client
        self.checkpoints = checkpoint_manager or CheckpointManager()

    async def execute(self, request: AgentRequest, workflow_def: Dict[str, Any]) -> AgentResponse:
        started_at = datetime.now()
        workflow_id = workflow_def.get("workflow_id") or request.parameters.get("workflow_id") or "custom_workflow"
        nodes = workflow_def.get("nodes") or []
        if not nodes:
            raise ValueError("workflow nodes are required")

        state = self._initial_state(request, workflow_def)
        if request.resume_from_checkpoint:
            state = self.checkpoints.load(request.project_id, request.resume_from_checkpoint)
            state["resume_count"] = int(state.get("resume_count") or 0) + 1
            state["status"] = "running"
            state["waiting_for_human"] = None

        output_refs: List[str] = []
        warnings: List[Dict[str, Any]] = []
        checkpoint_ref: Optional[str] = None

        try:
            while state["next_node_index"] < len(nodes):
                node = nodes[state["next_node_index"]]
                node_id = node.get("id") or f"node_{state['next_node_index'] + 1}"

                if not self._should_run(node, state):
                    state["node_results"][node_id] = {
                        "node_id": node_id,
                        "status": "skipped",
                        "reason": "condition_not_met",
                    }
                    state["next_node_index"] += 1
                    checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
                    continue

                node_type = node.get("type", "agent")

                if node_type == "human_confirmation":
                    confirmation = self._consume_human_confirmation(node_id, request)
                    if not confirmation.get("submitted"):
                        state["status"] = "waiting_for_human"
                        state["waiting_for_human"] = {
                            "node_id": node_id,
                            "prompt": node.get("prompt", "请确认是否继续工作流"),
                            "required": True,
                        }
                        checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
                        return self._response(
                            request,
                            started_at,
                            "partial",
                            output_refs + [checkpoint_ref],
                            state,
                            checkpoint_ref,
                            warnings=[{
                                "code": "WORKFLOW_WAITING_FOR_HUMAN",
                                "message": f"Workflow paused at human confirmation node: {node_id}",
                                "retryable": False,
                            }],
                        )

                    if not confirmation.get("approved"):
                        state["status"] = "cancelled"
                        state["waiting_for_human"] = None
                        state["node_results"][node_id] = {
                            "node_id": node_id,
                            "type": "human_confirmation",
                            "status": "cancelled",
                            "decision": confirmation,
                        }
                        checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
                        return self._response(
                            request,
                            started_at,
                            "cancelled",
                            output_refs + [checkpoint_ref],
                            state,
                            checkpoint_ref,
                            warnings=[{
                                "code": "WORKFLOW_HUMAN_REJECTED",
                                "message": f"Workflow cancelled by human confirmation node: {node_id}",
                                "retryable": False,
                            }],
                        )

                    state["node_results"][node_id] = {
                        "node_id": node_id,
                        "type": "human_confirmation",
                        "status": "success",
                        "decision": confirmation,
                    }
                    state["next_node_index"] += 1
                    checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
                    continue

                if node_type == "parallel":
                    parallel_result = await self._run_parallel_node(request, node, state, node_id)
                    state["node_results"][node_id] = parallel_result["summary"]
                    state["last_node_id"] = node_id
                    output_refs.extend(parallel_result["output_refs"])

                    if parallel_result["status"] != "success":
                        state["status"] = "failed"
                        state["failed_node_id"] = node_id
                        checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
                        return self._response(
                            request,
                            started_at,
                            "failed",
                            output_refs + [checkpoint_ref],
                            state,
                            checkpoint_ref,
                            errors=[{
                                "code": "WORKFLOW_PARALLEL_NODE_FAILED",
                                "message": f"Parallel node failed: {node_id}",
                                "node_id": node_id,
                                "failed_branches": parallel_result["failed_branches"],
                                "retryable": True,
                            }],
                        )

                    state["next_node_index"] += 1
                    checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
                    continue

                node_response, node_metadata = await self._run_agent_node(request, node, state, node_id)
                node_dump = self._node_response_dump(node_response, node_metadata)
                state["node_results"][node_id] = node_dump
                state["last_node_id"] = node_id
                output_refs.extend(node_response.output_refs or [])

                if node_response.status not in ("success", "partial"):
                    state["status"] = "failed"
                    state["failed_node_id"] = node_id
                    checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
                    return self._response(
                        request,
                        started_at,
                        "failed",
                        output_refs + [checkpoint_ref],
                        state,
                        checkpoint_ref,
                        errors=[{
                            "code": "WORKFLOW_NODE_FAILED",
                            "message": f"Workflow node failed: {node_id}",
                            "node_id": node_id,
                            "retryable": True,
                        }],
                    )

                state["next_node_index"] += 1
                checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)

            state["status"] = "success"
            checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
            return self._response(
                request,
                started_at,
                "success",
                output_refs + [checkpoint_ref],
                state,
                checkpoint_ref,
                warnings=warnings,
            )
        except Exception as exc:
            state["status"] = "failed"
            state["error"] = str(exc)
            checkpoint_ref = self._save_running_checkpoint(request, workflow_id, state)
            return self._response(
                request,
                started_at,
                "failed",
                output_refs + [checkpoint_ref],
                state,
                checkpoint_ref,
                errors=[{
                    "code": "WORKFLOW_ERROR",
                    "message": str(exc),
                    "retryable": True,
                }],
            )

    def _initial_state(self, request: AgentRequest, workflow_def: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "workflow_id": workflow_def.get("workflow_id") or request.parameters.get("workflow_id") or "custom_workflow",
            "workflow_name": workflow_def.get("workflow_name") or workflow_def.get("name") or "Custom Workflow",
            "task_id": request.task_id,
            "project_id": request.project_id,
            "status": "running",
            "nodes_total": len(workflow_def.get("nodes") or []),
            "input": {
                "input_refs": request.input_refs,
                "parameters": request.parameters,
                "config": request.config,
            },
            "next_node_index": 0,
            "node_results": {},
            "waiting_for_human": None,
            "resume_count": 0,
            "created_at": datetime.now().isoformat(),
        }

    def _save_running_checkpoint(self, request: AgentRequest, workflow_id: str, state: Dict[str, Any]) -> str:
        state["updated_at"] = datetime.now().isoformat()
        return self.checkpoints.save(request.project_id, request.task_id, workflow_id, state)

    async def _run_agent_node(
        self,
        request: AgentRequest,
        node: Dict[str, Any],
        state: Dict[str, Any],
        node_id: str,
    ) -> Tuple[AgentResponse, Dict[str, Any]]:
        agent_name = node.get("agent") or node.get("agent_name")
        if not agent_name:
            raise ValueError(f"Workflow node {node_id} is missing agent")

        registry = self.registry_provider()
        if agent_name not in registry:
            raise ValueError(f"Workflow node {node_id} references unknown agent: {agent_name}")

        task_type = node.get("task_type") or agent_name
        input_refs = self._resolve(node.get("input_refs", {}), request, state)
        parameters = self._resolve(node.get("parameters", {}), request, state)
        resolved_input_refs = input_refs if isinstance(input_refs, dict) else {}
        resolved_parameters = parameters if isinstance(parameters, dict) else {}
        node_request = AgentRequest(
            task_id=f"{request.task_id}_{node_id}",
            project_id=request.project_id,
            task_type=task_type,
            user_input=request.user_input,
            input_refs=resolved_input_refs,
            model_profile_id=request.model_profile_id,
            skill_ids=request.skill_ids,
            parameters=resolved_parameters,
            config=resolved_parameters,
        )
        node_metadata = {
            "node_id": node_id,
            "type": node.get("type", "agent"),
            "agent": agent_name,
            "task_type": task_type,
            "input_refs": resolved_input_refs,
            "parameters": resolved_parameters,
        }

        agent = registry[agent_name]
        if self.llm_client and hasattr(self.llm_client, "profile_context"):
            async with self.llm_client.profile_context(
                    request.model_profile_id,
                    task_type,
                    project_id=request.project_id,
                    task_id=node_request.task_id):
                response = await agent.run(node_request)
                return response, node_metadata
        response = await agent.run(node_request)
        return response, node_metadata

    def _node_response_dump(self, response: AgentResponse, node_metadata: Dict[str, Any]) -> Dict[str, Any]:
        dump = response.model_dump(mode="json")
        for key, value in node_metadata.items():
            dump.setdefault(key, value)
        return dump

    async def _run_parallel_node(
        self,
        request: AgentRequest,
        node: Dict[str, Any],
        state: Dict[str, Any],
        node_id: str,
    ) -> Dict[str, Any]:
        """Run a set of branches concurrently and aggregate their results.

        Branch results are written into ``node_results`` under each branch id so
        downstream ``${outputs.<branch_id>...}`` expressions keep working exactly
        like sequential nodes. The aggregated summary is stored under the parent
        parallel node id.

        Note: agents are singletons in the registry and share mutable
        ``self.metrics``, so per-agent token/llm metrics for concurrently running
        branches that reuse the SAME agent instance are approximate. Branch
        statuses and output_refs are accurate.
        """
        branches = node.get("branches") or node.get("nodes") or []
        if not branches:
            raise ValueError(f"Parallel node {node_id} has no branches")

        # Honor per-branch conditions: skip branches whose condition is not met.
        runnable: List[Dict[str, Any]] = []
        skipped: List[str] = []
        for index, branch in enumerate(branches):
            branch_id = branch.get("id") or f"{node_id}_branch_{index + 1}"
            if self._should_run(branch, state):
                runnable.append({"id": branch_id, "node": branch})
            else:
                skipped.append(branch_id)
                state["node_results"][branch_id] = {
                    "node_id": branch_id,
                    "status": "skipped",
                    "reason": "condition_not_met",
                }

        # fail_fast=True cancels still-running branches on the first failure.
        fail_fast = bool(node.get("fail_fast", False))

        async def run_branch(branch_node: Dict[str, Any], branch_id: str) -> Tuple[AgentResponse, Dict[str, Any]]:
            return await self._run_agent_node(request, branch_node, state, branch_id)

        tasks: Dict[asyncio.Task, str] = {}
        for item in runnable:
            task = asyncio.ensure_future(run_branch(item["node"], item["id"]))
            tasks[task] = item["id"]

        output_refs: List[str] = []
        failed_branches: List[str] = []
        branch_statuses: Dict[str, str] = {}
        cancelled = False
        pending = set(tasks.keys())

        while pending:
            done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                branch_id = tasks[task]
                try:
                    result, metadata = task.result()
                except Exception as exc:  # noqa: BLE001 - record and continue
                    branch_statuses[branch_id] = "failed"
                    failed_branches.append(branch_id)
                    state["node_results"][branch_id] = {
                        "node_id": branch_id,
                        "status": "failed",
                        "error": str(exc),
                    }
                    continue
                dump = self._node_response_dump(result, metadata)
                state["node_results"][branch_id] = dump
                branch_statuses[branch_id] = result.status
                output_refs.extend(result.output_refs or [])
                if result.status not in ("success", "partial"):
                    failed_branches.append(branch_id)

            if fail_fast and failed_branches and pending:
                cancelled = True
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                for task in pending:
                    branch_id = tasks[task]
                    branch_statuses.setdefault(branch_id, "cancelled")
                    state["node_results"].setdefault(branch_id, {
                        "node_id": branch_id,
                        "status": "cancelled",
                    })
                pending = set()

        status = "success" if not failed_branches else "failed"
        summary = {
            "node_id": node_id,
            "type": "parallel",
            "status": status,
            "branch_count": len(runnable),
            "skipped_branches": skipped,
            "branch_statuses": branch_statuses,
            "failed_branches": failed_branches,
            "fail_fast": fail_fast,
            "cancelled_remaining": cancelled,
        }
        return {
            "status": status,
            "summary": summary,
            "output_refs": output_refs,
            "failed_branches": failed_branches,
        }

    def _resolve(self, value: Any, request: AgentRequest, state: Dict[str, Any]) -> Any:
        if isinstance(value, str):
            match = EXPR_PATTERN.match(value)
            if match:
                return self._lookup(match.group(1), request, state)
            return value
        if isinstance(value, list):
            return [self._resolve(item, request, state) for item in value]
        if isinstance(value, dict):
            return {key: self._resolve(item, request, state) for key, item in value.items()}
        return value

    def _lookup(self, expression: str, request: AgentRequest, state: Dict[str, Any]) -> Any:
        roots = {
            "project_id": request.project_id,
            "task_id": request.task_id,
            "input": request.input_refs,
            "parameters": request.parameters,
            "config": request.config,
            "outputs": state.get("node_results", {}),
            "state": state,
        }
        parts = expression.split(".")
        current = roots.get(parts[0])
        if parts[0] in ("project_id", "task_id"):
            return current
        for part in parts[1:]:
            if isinstance(current, dict):
                current = current.get(part)
            elif isinstance(current, list) and part.isdigit():
                current = current[int(part)]
            else:
                return None
        return deepcopy(current)

    def _should_run(self, node: Dict[str, Any], state: Dict[str, Any]) -> bool:
        condition = node.get("condition")
        if not condition:
            return True
        return self._evaluate_condition(condition, state)

    def _evaluate_condition(self, condition: Any, state: Dict[str, Any]) -> bool:
        # Shorthand string conditions
        if isinstance(condition, str):
            if condition == "previous_success":
                last_node_id = state.get("last_node_id")
                if not last_node_id:
                    return True
                return state.get("node_results", {}).get(last_node_id, {}).get("status") == "success"
            if condition in ("always", "true"):
                return True
            if condition in ("never", "false"):
                return False
            # Bare expression -> truthiness of looked-up value
            return bool(self._lookup_from_state(condition, state))

        if not isinstance(condition, dict):
            return True

        # Boolean composition
        if "all_of" in condition:
            return all(self._evaluate_condition(sub, state) for sub in (condition.get("all_of") or []))
        if "any_of" in condition:
            return any(self._evaluate_condition(sub, state) for sub in (condition.get("any_of") or []))
        if "not" in condition:
            return not self._evaluate_condition(condition.get("not"), state)

        # Comparator form: {source, op, value}; op defaults to equals.
        source = condition.get("source")
        if source is None:
            return True
        actual = self._lookup_from_state(source, state)
        # "equals" key kept for backward compatibility (no explicit op)
        if "equals" in condition and "op" not in condition:
            return actual == condition.get("equals")
        op = condition.get("op", "equals")
        expected = condition.get("value", condition.get("equals"))
        return self._compare(actual, op, expected)

    def _compare(self, actual: Any, op: str, expected: Any) -> bool:
        try:
            if op in ("equals", "eq", "=="):
                return actual == expected
            if op in ("not_equals", "ne", "!="):
                return actual != expected
            if op in ("gt", ">"):
                return actual is not None and actual > expected
            if op in ("gte", ">="):
                return actual is not None and actual >= expected
            if op in ("lt", "<"):
                return actual is not None and actual < expected
            if op in ("lte", "<="):
                return actual is not None and actual <= expected
            if op == "in":
                return expected is not None and actual in expected
            if op == "not_in":
                return expected is not None and actual not in expected
            if op == "contains":
                return actual is not None and expected in actual
            if op in ("exists", "is_set"):
                return actual is not None
            if op == "truthy":
                return bool(actual)
        except TypeError:
            return False
        return False

    def _lookup_from_state(self, expression: str, state: Dict[str, Any]) -> Any:
        if not expression:
            return None
        parts = expression.split(".")
        # Allow "outputs." as a friendly alias for node_results, matching ${outputs...}
        if parts[0] == "outputs":
            current: Any = state.get("node_results", {})
            parts = parts[1:]
        else:
            current = state
        for part in parts:
            if isinstance(current, dict):
                current = current.get(part)
            elif isinstance(current, list) and part.isdigit():
                current = current[int(part)]
            else:
                return None
        return current

    def _consume_human_confirmation(self, node_id: str, request: AgentRequest) -> Dict[str, Any]:
        missing = object()
        if "resume_input" in request.parameters:
            resume_input = request.parameters.get("resume_input")
        elif "resume_input" in request.config:
            resume_input = request.config.get("resume_input")
        else:
            return {"submitted": False, "approved": False}

        value: Any = missing
        confirmations = resume_input.get("human_confirmations") if isinstance(resume_input, dict) else None
        if isinstance(confirmations, dict) and node_id in confirmations:
            value = confirmations[node_id]
        elif isinstance(resume_input, dict) and node_id in resume_input:
            value = resume_input[node_id]
        elif isinstance(resume_input, dict) and any(key in resume_input for key in ("approved", "continue", "decision")):
            value = resume_input
        elif isinstance(resume_input, bool):
            value = resume_input

        if value is missing:
            return {"submitted": False, "approved": False}

        if isinstance(value, bool):
            return {"submitted": True, "approved": value}
        if isinstance(value, dict):
            approved = value.get("approved", value.get("continue"))
            if approved is None and "decision" in value:
                approved = str(value.get("decision")).lower() in {"approve", "approved", "yes", "true", "continue"}
            result = dict(value)
            result["submitted"] = True
            result["approved"] = bool(approved)
            return result
        return {"submitted": False, "approved": False}

    def _response(
        self,
        request: AgentRequest,
        started_at: datetime,
        status: str,
        output_refs: List[str],
        state: Dict[str, Any],
        checkpoint_ref: Optional[str],
        errors: Optional[List[Dict[str, Any]]] = None,
        warnings: Optional[List[Dict[str, Any]]] = None,
    ) -> AgentResponse:
        completed_nodes = [
            node_id
            for node_id, result in state.get("node_results", {}).items()
            if result.get("status") in ("success", "partial", "skipped")
        ]
        return AgentResponse(
            task_id=request.task_id,
            agent_name="WorkflowAgent",
            project_id=request.project_id,
            status=status,
            output_refs=output_refs,
            structured_output={
                "workflow_id": state.get("workflow_id"),
                "workflow_name": state.get("workflow_name"),
                "status": state.get("status"),
                "next_node_index": state.get("next_node_index"),
                "completed_nodes": completed_nodes,
                "waiting_for_human": state.get("waiting_for_human"),
                "node_results": state.get("node_results", {}),
                "checkpoint_ref": checkpoint_ref,
            },
            errors=errors or [],
            warnings=warnings or [],
            metrics={
                "workflow_nodes_total": state.get("nodes_total", len(state.get("node_results", {}))),
                "workflow_nodes_completed": len(completed_nodes),
                "resume_count": state.get("resume_count", 0),
                "duration_ms": int((datetime.now() - started_at).total_seconds() * 1000),
            },
            checkpoint_ref=checkpoint_ref,
            created_at=started_at,
            finished_at=datetime.now(),
        )


class WorkflowAgent(BaseAgent):
    """Agent wrapper that lets Java run workflows through the existing task API."""

    def __init__(self, registry_provider: Callable[[], Dict[str, BaseAgent]], llm_client: Any = None):
        super().__init__("WorkflowAgent")
        self.supported_tasks = [
            "workflow",
            "sample_analysis_workflow",
            "chapter_workflow",
            "parallel_sample_analysis",
            "memory_graph_refresh",
            "chapter_pipeline",
        ]
        self.engine = WorkflowEngine(registry_provider, llm_client=llm_client)

    async def run(self, request: AgentRequest) -> AgentResponse:
        await self.validate_request(request)
        workflow_def = self._resolve_workflow_definition(request)
        return await self.engine.execute(request, workflow_def)

    def _resolve_workflow_definition(self, request: AgentRequest) -> Dict[str, Any]:
        workflow_def = (
            request.parameters.get("workflow_definition")
            or request.parameters.get("workflow")
            or request.config.get("workflow_definition")
            or request.config.get("workflow")
        )
        if isinstance(workflow_def, dict):
            return workflow_def

        workflow_id = (
            request.parameters.get("workflow_id")
            or request.config.get("workflow_id")
            or request.task_type
        )
        definitions = default_workflows()
        if workflow_id not in definitions:
            raise ValueError(f"Unknown workflow_id: {workflow_id}")
        return definitions[workflow_id]


def default_workflows() -> Dict[str, Dict[str, Any]]:
    """Built-in workflows exposed as stable IDs."""

    return {
        "sample_analysis": {
            "workflow_id": "sample_analysis",
            "workflow_name": "样本分析工作流",
            "nodes": [
                {
                    "id": "full_text_analysis",
                    "type": "agent",
                    "agent": "full_text_analysis",
                    "task_type": "full_text_analysis",
                    "input_refs": {"sample_id": "${input.sample_id}"},
                    "parameters": {"sample_id": "${input.sample_id}"},
                },
                {
                    "id": "coverage_check",
                    "type": "agent",
                    "agent": "coverage_check",
                    "task_type": "coverage_check",
                    "input_refs": {"sample_id": "${input.sample_id}"},
                    "parameters": {"sample_id": "${input.sample_id}"},
                },
                {
                    "id": "book_summary",
                    "type": "agent",
                    "agent": "book_summary",
                    "task_type": "book_summary",
                    "input_refs": {"sample_id": "${input.sample_id}"},
                    "parameters": {"sample_id": "${input.sample_id}"},
                },
            ],
        },
        "retrieval_refresh": {
            "workflow_id": "retrieval_refresh",
            "workflow_name": "检索索引刷新工作流",
            "nodes": [
                {
                    "id": "retrieval_index",
                    "type": "agent",
                    "agent": "retrieval_index",
                    "task_type": "retrieval_index",
                    "input_refs": {},
                    "parameters": {},
                }
            ],
        },
        "human_review_gate": {
            "workflow_id": "human_review_gate",
            "workflow_name": "人工确认示例工作流",
            "nodes": [
                {
                    "id": "wait_for_approval",
                    "type": "human_confirmation",
                    "prompt": "请确认是否继续后续节点",
                },
                {
                    "id": "retrieval_index_after_approval",
                    "type": "agent",
                    "agent": "retrieval_index",
                    "task_type": "retrieval_index",
                    "input_refs": {},
                    "parameters": {},
                },
            ],
        },
        "parallel_sample_analysis": {
            "workflow_id": "parallel_sample_analysis",
            "workflow_name": "并行多样本分析工作流",
            "nodes": [
                {
                    "id": "analyze_samples",
                    "type": "parallel",
                    "fail_fast": False,
                    "branches": [
                        {
                            "id": "analyze_sample_a",
                            "agent": "full_text_analysis",
                            "task_type": "full_text_analysis",
                            "input_refs": {"sample_id": "${input.sample_id_a}"},
                            "parameters": {"sample_id": "${input.sample_id_a}"},
                        },
                        {
                            "id": "analyze_sample_b",
                            "agent": "full_text_analysis",
                            "task_type": "full_text_analysis",
                            "input_refs": {"sample_id": "${input.sample_id_b}"},
                            "parameters": {"sample_id": "${input.sample_id_b}"},
                        },
                    ],
                },
                {
                    "id": "cross_book_synthesis",
                    "type": "agent",
                    "agent": "cross_book_synthesis",
                    "task_type": "cross_book_synthesis",
                    "condition": {"source": "outputs.analyze_samples.status", "equals": "success"},
                    "input_refs": {},
                    "parameters": {},
                },
            ],
        },
        "memory_graph_refresh": {
            "workflow_id": "memory_graph_refresh",
            "workflow_name": "记忆与图谱并行刷新工作流",
            "nodes": [
                {
                    "id": "extract_memory",
                    "type": "agent",
                    "agent": "memory_extraction",
                    "task_type": "memory_extraction",
                    "input_refs": {"book_id": "${input.book_id}"},
                    "parameters": {"book_id": "${input.book_id}"},
                },
                {
                    "id": "build_downstream",
                    "type": "parallel",
                    "fail_fast": False,
                    "condition": {"source": "outputs.extract_memory.status", "op": "in", "value": ["success", "partial"]},
                    "branches": [
                        {
                            "id": "build_graph",
                            "agent": "graph_build",
                            "task_type": "graph_build",
                            "input_refs": {"book_id": "${input.book_id}"},
                            "parameters": {"book_id": "${input.book_id}"},
                        },
                        {
                            "id": "rebuild_retrieval",
                            "agent": "retrieval_index",
                            "task_type": "retrieval_index",
                            "input_refs": {},
                            "parameters": {},
                        },
                    ],
                },
            ],
        },
        "chapter_pipeline": {
            "workflow_id": "chapter_pipeline",
            "workflow_name": "章节创作流水线工作流",
            "nodes": [
                {
                    "id": "write_chapter",
                    "type": "agent",
                    "agent": "chapter_writing",
                    "task_type": "chapter_writing",
                    "input_refs": {
                        "book_id": "${input.book_id}",
                        "volume_number": "${input.volume_number}",
                        "chapter_number": "${input.chapter_number}",
                    },
                    "parameters": {
                        "book_id": "${input.book_id}",
                        "volume_number": "${input.volume_number}",
                        "chapter_number": "${input.chapter_number}",
                        "auto_review": True,
                    },
                },
                {
                    "id": "revise_chapter",
                    "type": "agent",
                    "agent": "chapter_revision",
                    "task_type": "chapter_revision",
                    "condition": {"source": "outputs.write_chapter.structured_output.boundary_passed", "equals": False},
                    "input_refs": {
                        "book_id": "${input.book_id}",
                        "volume_number": "${input.volume_number}",
                        "chapter_number": "${input.chapter_number}",
                    },
                    "parameters": {
                        "book_id": "${input.book_id}",
                        "volume_number": "${input.volume_number}",
                        "chapter_number": "${input.chapter_number}",
                    },
                },
                {
                    "id": "wait_for_finalize_approval",
                    "type": "human_confirmation",
                    "prompt": "请确认章节质量并批准进入记忆摄取",
                },
                {
                    "id": "ingest_memory",
                    "type": "agent",
                    "agent": "memory_extraction",
                    "task_type": "memory_extraction",
                    "input_refs": {"book_id": "${input.book_id}"},
                    "parameters": {"book_id": "${input.book_id}"},
                },
            ],
        },
    }
