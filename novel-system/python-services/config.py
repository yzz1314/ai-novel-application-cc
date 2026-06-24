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
