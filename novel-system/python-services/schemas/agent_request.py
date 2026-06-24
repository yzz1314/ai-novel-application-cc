"""Agent请求模型"""
from pydantic import BaseModel
from typing import Dict, Any, List, Optional

class AgentRequest(BaseModel):
    task_id: str
    project_id: str
    task_type: str
    user_input: Optional[str] = None
    input_refs: Dict[str, Any] = {}
    model_profile_id: Optional[str] = None
    skill_ids: List[str] = []
    parameters: Dict[str, Any] = {}
    resume_from_checkpoint: Optional[str] = None
    
    class Config:
        json_schema_extra = {
            "example": {
                "task_id": "task_001",
                "project_id": "proj_001",
                "task_type": "chapter_writing",
                "input_refs": {"chapter_number": 5},
                "skill_ids": ["project_chapter_writing"],
                "parameters": {"mode": "deep"}
            }
        }
