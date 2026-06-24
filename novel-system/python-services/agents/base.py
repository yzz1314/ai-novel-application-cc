"""Agent基类"""
from abc import ABC, abstractmethod
from typing import Dict, Any, List
from datetime import datetime
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse

class BaseAgent(ABC):
    """Agent基类"""
    
    def __init__(self, agent_name: str):
        self.agent_name = agent_name
        self.supported_tasks: List[str] = []
        self.metrics = {
            "llm_calls": 0,
            "input_tokens": 0,
            "output_tokens": 0,
            "start_time": None
        }
    
    @abstractmethod
    async def run(self, request: AgentRequest) -> AgentResponse:
        """执行Agent任务的主入口"""
        pass
    
    async def validate_request(self, request: AgentRequest) -> bool:
        """验证请求参数"""
        if not request.task_id:
            raise ValueError("task_id is required")
        if not request.project_id:
            raise ValueError("project_id is required")
        return True
    
    def _build_response(self, request: AgentRequest, status: str,
                       output_refs: List[str] = None,
                       structured_output: Dict[str, Any] = None,
                       errors: List = None,
                       warnings: List = None) -> AgentResponse:
        """构建统一的响应对象"""
        duration_ms = 0
        if self.metrics["start_time"]:
            duration_ms = int((datetime.now() - self.metrics["start_time"]).total_seconds() * 1000)
        
        return AgentResponse(
            task_id=request.task_id,
            agent_name=self.agent_name,
            project_id=request.project_id,
            status=status,
            output_refs=output_refs or [],
            structured_output=structured_output or {},
            errors=errors or [],
            warnings=warnings or [],
            metrics={
                "llm_calls": self.metrics["llm_calls"],
                "input_tokens": self.metrics["input_tokens"],
                "output_tokens": self.metrics["output_tokens"],
                "duration_ms": duration_ms
            },
            created_at=self.metrics["start_time"],
            finished_at=datetime.now()
        )
