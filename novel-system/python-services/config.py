"""配置管理"""
import os
from pydantic import ConfigDict
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    model_config = ConfigDict(env_file=".env", extra="ignore")

    # 项目路径
    PROJECT_BASE_PATH: str = os.getenv("PROJECT_BASE_PATH", "/workspace")
    SKILLS_PATH: str = os.getenv("SKILLS_PATH", "/workspace/skills")

    # 模型配置
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
    OPENAI_API_BASE: str = os.getenv("OPENAI_API_BASE", os.getenv("OPENAI_API_BASE_URL", ""))
    ANTHROPIC_API_KEY: str = os.getenv("ANTHROPIC_API_KEY", "")
    DEFAULT_MODEL: str = os.getenv("DEFAULT_MODEL", "gpt-4")
    DEFAULT_EMBEDDING_MODEL: str = os.getenv("DEFAULT_EMBEDDING_MODEL", "text-embedding-ada-002")
    MOCK_LLM: bool = os.getenv("MOCK_LLM", "false").lower() in ("1", "true", "yes", "on")

    # Vector store
    PGVECTOR_DSN: str = os.getenv(
        "PGVECTOR_DSN",
        os.getenv("PGVECTOR_DATABASE_URL", os.getenv("DATABASE_URL", ""))
    )
    
    # 分块配置
    MAX_CHUNK_SIZE: int = int(os.getenv("MAX_CHUNK_SIZE", "2500"))
    CHUNK_OVERLAP: int = int(os.getenv("CHUNK_OVERLAP", "200"))
    
    # 并发配置
    MAX_CONCURRENT_TASKS: int = int(os.getenv("MAX_CONCURRENT_TASKS", "3"))
    
    # 日志配置
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    
settings = Settings()
