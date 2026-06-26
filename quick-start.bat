@echo off
chcp 65001 >nul
echo 🚀 快速启动AI小说创作系统...
echo.

set PROJECT_ROOT=%~dp0
cd /d %PROJECT_ROOT%

REM 启动Python AI服务
echo [1/3] 启动Python AI服务 (http://localhost:8000)
start "Python AI" cmd /k "cd /d %PROJECT_ROOT%novel-system\python-services && uvicorn main:app --reload"
timeout /t 2 /nobreak >nul

REM 启动Java后端
echo [2/3] 启动Java后端 (http://localhost:8080)
start "Java Backend" cmd /k "cd /d %PROJECT_ROOT%novel-system\backend && mvn spring-boot:run"
timeout /t 3 /nobreak >nul

REM 启动React前端
echo [3/3] 启动React前端 (http://localhost:5173)
start "React Frontend" cmd /k "cd /d %PROJECT_ROOT%novel-system\frontend && npm run dev"

echo.
echo ✅ 所有服务已启动！
echo.
echo 等待30秒后自动打开浏览器...
timeout /t 30 /nobreak

start http://localhost:5173

exit
