"""Agent routes."""
import asyncio
from datetime import datetime

from fastapi import APIRouter, HTTPException

from agents import (
    AnalysisRepairAgent,
    BookSummaryAgent,
    ChapterWriterAgent,
    CoverageCheckAgent,
    CrossBookSynthesisAgent,
    FullTextAnalysisAgent,
    GraphBuilderAgent,
    MemoryExtractorAgent,
    MemoryQueryAgent,
    OutlineGeneratorAgent,
    OutlineReviewAgent,
    RetrievalIndexAgent,
    RevisionAgent,
    SampleImportAgent,
    SkillGeneratorAgent,
)
from llm.client import LLMClient
from orchestrator import WorkflowAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from utils.logger import get_logger
from utils.task_cancellation import cancellation_registry

router = APIRouter()
logger = get_logger("AgentRoutes")

llm_client = LLMClient()

AGENT_REGISTRY = {
    "sample_import": SampleImportAgent(),
    "full_text_analysis": FullTextAnalysisAgent(llm_client=llm_client),
    "book_summary": BookSummaryAgent(llm_client=llm_client),
    "cross_book_synthesis": CrossBookSynthesisAgent(llm_client=llm_client),
    "skill_generation": SkillGeneratorAgent(llm_client=llm_client),
    "outline_generation": OutlineGeneratorAgent(llm_client=llm_client),
    "outline_review": OutlineReviewAgent(llm_client=llm_client),
    "chapter_writing": ChapterWriterAgent(llm_client=llm_client),
    "chapter_revision": RevisionAgent(llm_client=llm_client),
    "memory_extraction": MemoryExtractorAgent(llm_client=llm_client),
    "memory_query": MemoryQueryAgent(llm_client=llm_client),
    "graph_build": GraphBuilderAgent(),
    "coverage_check": CoverageCheckAgent(),
    "analysis_repair": AnalysisRepairAgent(llm_client=llm_client),
    "retrieval_index": RetrievalIndexAgent(llm_client=llm_client),
    "workflow": WorkflowAgent(lambda: AGENT_REGISTRY, llm_client=llm_client),
}

logger.info(f"Registered {len(AGENT_REGISTRY)} agents: {list(AGENT_REGISTRY.keys())}")


@router.get("/list")
async def list_agents():
    """List all registered agents."""
    agents_info = []
    for name, agent in AGENT_REGISTRY.items():
        agents_info.append({
            "name": name,
            "agent_class": agent.agent_name,
            "supported_tasks": agent.supported_tasks,
        })
    return {"agents": agents_info, "total": len(agents_info)}


@router.post("/{agent_name}/run", response_model=AgentResponse)
async def run_agent(agent_name: str, request: AgentRequest):
    """Execute a registered agent and expose cooperative cancellation."""
    if agent_name not in AGENT_REGISTRY:
        raise HTTPException(status_code=404, detail=f"Agent not found: {agent_name}")

    started_at = datetime.now()
    try:
        agent = AGENT_REGISTRY[agent_name]

        async def run_registered_agent():
            async with llm_client.profile_context(
                    request.model_profile_id,
                    request.task_type,
                    project_id=request.project_id,
                    task_id=request.task_id):
                return await agent.run(request)

        running_task = asyncio.create_task(run_registered_agent())
        await cancellation_registry.register(request.task_id, running_task)
        try:
            response = await running_task
        finally:
            await cancellation_registry.unregister(request.task_id)
        return response
    except asyncio.CancelledError:
        await cancellation_registry.unregister(request.task_id)
        return AgentResponse(
            task_id=request.task_id,
            agent_name=AGENT_REGISTRY[agent_name].agent_name,
            project_id=request.project_id,
            status="cancelled",
            output_refs=[],
            structured_output={
                "cancelled": True,
                "task_id": request.task_id,
                "message": "Task was cancelled by request",
            },
            errors=[],
            warnings=[{
                "code": "TASK_CANCELLED",
                "message": "Task was cancelled by request",
                "retryable": False,
            }],
            metrics={"duration_ms": int((datetime.now() - started_at).total_seconds() * 1000)},
            created_at=started_at,
            finished_at=datetime.now(),
        )
    except Exception as exc:
        await cancellation_registry.unregister(request.task_id)
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/tasks/{task_id}/cancel")
async def cancel_task(task_id: str):
    """Cancel a running in-process agent task."""
    return await cancellation_registry.cancel(task_id)
