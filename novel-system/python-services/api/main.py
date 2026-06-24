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
