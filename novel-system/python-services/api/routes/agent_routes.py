"""Agent路由"""
from fastapi import APIRouter, HTTPException
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse

router = APIRouter()

# Agent注册表（后续会动态加载）
AGENT_REGISTRY = {}

@router.post("/{agent_name}/run", response_model=AgentResponse)
async def run_agent(agent_name: str, request: AgentRequest):
    """执行指定Agent"""
    if agent_name not in AGENT_REGISTRY:
        raise HTTPException(status_code=404, detail=f"Agent not found: {agent_name}")
    
    try:
        agent = AGENT_REGISTRY[agent_name]
        response = await agent.run(request)
        return response
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/tasks/{task_id}/cancel")
async def cancel_task(task_id: str):
    """取消任务"""
    # TODO: 实现任务取消逻辑
    return {"status": "cancelled", "task_id": task_id}
