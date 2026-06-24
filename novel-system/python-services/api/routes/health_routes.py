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
