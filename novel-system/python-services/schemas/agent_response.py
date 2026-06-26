"""Agent响应模型"""
from pydantic import BaseModel
from typing import Dict, Any, List, Optional
from datetime import datetime

class AgentResponse(BaseModel):
    task_id: str
    agent_name: str
    project_id: str
    status: str  # success, failed, partial, cancelled
    output_refs: List[str] = []
    structured_output: Dict[str, Any] = {}
    errors: List[Dict[str, Any]] = []
    warnings: List[Dict[str, Any]] = []
    metrics: Dict[str, Any] = {}
    checkpoint_ref: Optional[str] = None
    created_at: datetime
    finished_at: datetime
    
    class Config:
        json_schema_extra = {
            "example": {
                "task_id": "task_001",
                "agent_name": "ChapterWritingAgent",
                "project_id": "proj_001",
                "status": "success",
                "output_refs": ["chapters/drafts/chapter_5.md"],
                "metrics": {
                    "llm_calls": 2,
                    "input_tokens": 5000,
                    "output_tokens": 3200
                }
            }
        }
