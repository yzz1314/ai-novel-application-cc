#!/bin/bash

# Python服务骨架创建脚本

set -e

echo "🐍 创建Python服务骨架..."

cd novel-system/python-services

# 创建目录结构
mkdir -p api/routes
mkdir -p agents
mkdir -p schemas
mkdir -p llm
mkdir -p retrieval
mkdir -p memory
mkdir -p graph
mkdir -p text_processing
mkdir -p skills/builtin
mkdir -p utils
mkdir -p tests

# requirements.txt
cat > requirements.txt << 'REQUIREMENTS'
# FastAPI
fastapi==0.109.0
uvicorn[standard]==0.27.0
pydantic==2.5.3
pydantic-settings==2.1.0

# LLM
litellm==1.17.0
openai==1.10.0
anthropic==0.8.1

# Agent Framework (可选)
langgraph==0.0.28
langchain==0.1.1
langchain-core==0.1.10

# Vector Store
lancedb==0.4.3
sentence-transformers==2.3.1

# Graph
networkx==3.2.1

# Text Processing
jieba==0.42.1

# Utilities
aiofiles==23.2.1
python-multipart==0.0.6
httpx==0.26.0
tenacity==8.2.3

# Development
pytest==7.4.4
pytest-asyncio==0.23.3
black==23.12.1
REQUIREMENTS

# main.py
cat > main.py << 'PYTHON'
"""
Novel System AI Service
小说创作系统 - AI服务入口
"""
import uvicorn
from api.main import app

if __name__ == "__main__":
    uvicorn.run(
        "api.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        log_level="info"
    )
PYTHON

# config.py
cat > config.py << 'PYTHON'
"""配置管理"""
import os
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    # 项目路径
    PROJECT_BASE_PATH: str = os.getenv("PROJECT_BASE_PATH", "/workspace")
    
    # 模型配置
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
    ANTHROPIC_API_KEY: str = os.getenv("ANTHROPIC_API_KEY", "")
    DEFAULT_MODEL: str = "gpt-4"
    DEFAULT_EMBEDDING_MODEL: str = "text-embedding-ada-002"
    
    # 分块配置
    MAX_CHUNK_SIZE: int = int(os.getenv("MAX_CHUNK_SIZE", "2500"))
    CHUNK_OVERLAP: int = int(os.getenv("CHUNK_OVERLAP", "200"))
    
    # 并发配置
    MAX_CONCURRENT_TASKS: int = int(os.getenv("MAX_CONCURRENT_TASKS", "3"))
    
    # 日志配置
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    
    class Config:
        env_file = ".env"

settings = Settings()
PYTHON

# api/main.py
mkdir -p api
cat > api/main.py << 'PYTHON'
"""FastAPI应用主入口"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from api.routes import agent_routes, health_routes

app = FastAPI(
    title="Novel System AI Service",
    description="小说创作系统 - AI服务API",
    version="1.0.0"
)

# CORS配置
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(health_routes.router, tags=["health"])
app.include_router(agent_routes.router, prefix="/api/agents", tags=["agents"])

@app.on_event("startup")
async def startup_event():
    print("🚀 Novel System AI Service started")

@app.on_event("shutdown")
async def shutdown_event():
    print("👋 Novel System AI Service stopped")
PYTHON

# api/routes/health_routes.py
cat > api/routes/health_routes.py << 'PYTHON'
"""健康检查路由"""
from fastapi import APIRouter

router = APIRouter()

@router.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "healthy",
        "service": "novel-system-python",
        "version": "1.0.0"
    }

@router.get("/")
async def root():
    """根路径"""
    return {
        "message": "Novel System AI Service",
        "docs": "/docs"
    }
PYTHON

# api/routes/agent_routes.py
cat > api/routes/agent_routes.py << 'PYTHON'
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
PYTHON

# schemas/agent_request.py
cat > schemas/agent_request.py << 'PYTHON'
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
PYTHON

# schemas/agent_response.py
cat > schemas/agent_response.py << 'PYTHON'
"""Agent响应模型"""
from pydantic import BaseModel
from typing import Dict, Any, List
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
PYTHON

# agents/base.py
cat > agents/base.py << 'PYTHON'
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
PYTHON

# utils/logger.py
cat > utils/logger.py << 'PYTHON'
"""日志工具"""
import logging
import sys

def get_logger(name: str) -> logging.Logger:
    """获取logger"""
    logger = logging.getLogger(name)
    
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        formatter = logging.Formatter(
            '%(asctime)s - %(name)s - %(levelname)s - %(message)s'
        )
        handler.setFormatter(formatter)
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
    
    return logger
PYTHON

# Dockerfile
cat > Dockerfile << 'DOCKERFILE'
FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 复制依赖文件
COPY requirements.txt .

# 安装Python依赖
RUN pip install --no-cache-dir -r requirements.txt

# 复制应用代码
COPY . .

# 暴露端口
EXPOSE 8000

# 启动应用
CMD ["python", "main.py"]
DOCKERFILE

# .dockerignore
cat > .dockerignore << 'DOCKERIGNORE'
__pycache__/
*.pyc
*.pyo
*.pyd
.Python
*.so
.pytest_cache/
.coverage
htmlcov/
dist/
build/
*.egg-info/
.env
.venv
venv/
.git/
.idea/
.vscode/
DOCKERIGNORE

# pytest.ini
cat > pytest.ini << 'PYTEST'
[pytest]
testpaths = tests
python_files = test_*.py
python_classes = Test*
python_functions = test_*
asyncio_mode = auto
PYTEST

# __init__.py files
touch api/__init__.py
touch api/routes/__init__.py
touch agents/__init__.py
touch schemas/__init__.py
touch llm/__init__.py
touch retrieval/__init__.py
touch memory/__init__.py
touch graph/__init__.py
touch text_processing/__init__.py
touch skills/__init__.py
touch utils/__init__.py

echo "✅ Python服务骨架创建完成"

