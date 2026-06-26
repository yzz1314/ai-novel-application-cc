"""Agent路由"""
from fastapi import APIRouter, HTTPException
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from agents import (
    SampleImportAgent,
    FullTextAnalysisAgent,
    BookSummaryAgent,
    CrossBookSynthesisAgent,
    SkillGeneratorAgent,
    OutlineGeneratorAgent,
    OutlineReviewAgent,
    ChapterWriterAgent,
    RevisionAgent,
    MemoryExtractorAgent,
    MemoryQueryAgent,
    GraphBuilderAgent,
    CoverageCheckAgent,
    AnalysisRepairAgent,
    RetrievalIndexAgent
)
from llm.client import LLMClient
from orchestrator import WorkflowAgent
from utils.logger import get_logger

router = APIRouter()
logger = get_logger("AgentRoutes")

# 初始化LLM客户端（所有需要LLM的Agent共享）
llm_client = LLMClient()

# Agent注册表
AGENT_REGISTRY = {
    "sample_import": SampleImportAgent(),
    "full_text_analysis": FullTextAnalysisAgent(llm_client=llm_client),
    "book_summary": BookSummaryAgent(llm_client=llm_client),
    "cross_book_synthesis": CrossBookSynthesisAgent(llm_client=llm_client),
    "skill_generation": SkillGeneratorAgent(llm_client=llm_client),
    "outline_generation": OutlineGeneratorAgent(llm_client=llm_client),
    "outline_review": OutlineReviewAgent(),
    "chapter_writing": ChapterWriterAgent(llm_client=llm_client),
    "chapter_revision": RevisionAgent(llm_client=llm_client),
    "memory_extraction": MemoryExtractorAgent(llm_client=llm_client),
    "memory_query": MemoryQueryAgent(llm_client=llm_client),
    "graph_build": GraphBuilderAgent(),
    "coverage_check": CoverageCheckAgent(),
    "analysis_repair": AnalysisRepairAgent(llm_client=llm_client),
    "retrieval_index": RetrievalIndexAgent(),
    "workflow": WorkflowAgent(lambda: AGENT_REGISTRY, llm_client=llm_client)
}

logger.info(f"Registered {len(AGENT_REGISTRY)} agents: {list(AGENT_REGISTRY.keys())}")


@router.get("/list")
async def list_agents():
    """列出所有可用的Agent"""
    agents_info = []
    for name, agent in AGENT_REGISTRY.items():
        agents_info.append({
            "name": name,
            "agent_class": agent.agent_name,
            "supported_tasks": agent.supported_tasks
        })
    return {"agents": agents_info, "total": len(agents_info)}


@router.post("/{agent_name}/run", response_model=AgentResponse)
async def run_agent(agent_name: str, request: AgentRequest):
    """执行指定Agent"""
    if agent_name not in AGENT_REGISTRY:
        raise HTTPException(status_code=404, detail=f"Agent not found: {agent_name}")
    
    try:
        agent = AGENT_REGISTRY[agent_name]
        async with llm_client.profile_context(request.model_profile_id, request.task_type):
            response = await agent.run(request)
        return response
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/tasks/{task_id}/cancel")
async def cancel_task(task_id: str):
    """取消任务"""
    # TODO: 实现任务取消逻辑
    return {"status": "cancelled", "task_id": task_id}
