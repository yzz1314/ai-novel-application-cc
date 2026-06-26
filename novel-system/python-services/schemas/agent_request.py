"""Agent请求模型"""
from pydantic import BaseModel, Field, model_validator
from typing import Dict, Any, List, Optional

class AgentRequest(BaseModel):
    task_id: str
    project_id: str
    task_type: str
    user_input: Optional[str] = None
    input_refs: Dict[str, Any] = Field(default_factory=dict)
    model_profile_id: Optional[str] = None
    skill_ids: List[str] = Field(default_factory=list)
    parameters: Dict[str, Any] = Field(default_factory=dict)
    config: Dict[str, Any] = Field(default_factory=dict)
    resume_from_checkpoint: Optional[str] = None

    @model_validator(mode="after")
    def sync_parameters_and_config(self):
        """Keep legacy config clients and newer parameters clients compatible."""
        if self.config and not self.parameters:
            self.parameters = dict(self.config)
        elif self.parameters and not self.config:
            self.config = dict(self.parameters)
        return self
    
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
