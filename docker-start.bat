@echo off
chcp 65001 >nul
echo ╔══════════════════════════════════════════════════════╗
echo ║     AI小说创作系统 - Docker启动                      ║
echo ╚══════════════════════════════════════════════════════╝
echo.

cd /d %~dp0

echo [1/3] 检查Docker...
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Docker未安装或未启动
    echo 请先安装并启动Docker Desktop
    pause
    exit /b 1
)
echo ✅ Docker已就绪

echo.
echo [2/3] 启动所有服务...
docker-compose up -d

if %errorlevel% neq 0 (
    echo ❌ 启动失败
    pause
    exit /b 1
)

echo.
echo [3/3] 查看服务状态...
timeout /t 5 /nobreak >nul
docker-compose ps

echo.
echo ╔══════════════════════════════════════════════════════╗
echo ║              启动完成！                              ║
echo ╚══════════════════════════════════════════════════════╝
echo.
echo 服务地址:
echo   前端界面: http://localhost
echo   Java后端: http://localhost:8080
echo   Python AI: http://localhost:8000
echo   API文档: http://localhost:8000/docs
echo.
echo 按任意键查看实时日志...
pause >nul

docker-compose logs -f
