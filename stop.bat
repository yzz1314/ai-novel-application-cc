@echo off
chcp 65001 >nul
echo 🛑 停止AI小说创作系统...
echo.

echo 正在查找并停止服务...
echo.

REM 停止Python服务 (端口8000)
echo [1/3] 停止Python AI服务 (端口8000)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8000 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    if not errorlevel 1 echo ✅ Python AI服务已停止
)

REM 停止Java服务 (端口8080)
echo [2/3] 停止Java后端服务 (端口8080)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    if not errorlevel 1 echo ✅ Java后端服务已停止
)

REM 停止React服务 (端口5173)
echo [3/3] 停止React前端 (端口5173)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :5173 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    if not errorlevel 1 echo ✅ React前端已停止
)

echo.
echo ✅ 所有服务已停止！
echo.
pause
