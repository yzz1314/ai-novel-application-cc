@echo off
chcp 65001 >nul
echo ╔══════════════════════════════════════════════════════╗
echo ║       AI小说创作系统 - 自动启动脚本                  ║
echo ╚══════════════════════════════════════════════════════╝
echo.

REM 设置项目根目录
set PROJECT_ROOT=%~dp0
cd /d %PROJECT_ROOT%

echo [1/4] 检查前置条件...
echo.

REM 检查Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Python未安装或未添加到PATH
    pause
    exit /b 1
)
echo ✅ Python已安装

REM 检查Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Java未安装或未添加到PATH
    pause
    exit /b 1
)
echo ✅ Java已安装

REM 检查Node.js
node --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Node.js未安装或未添加到PATH
    pause
    exit /b 1
)
echo ✅ Node.js已安装

echo.
echo [2/4] 检查配置文件...
echo.

REM 检查.env文件
if not exist "novel-system\python-services\.env" (
    echo ❌ 未找到.env配置文件
    echo 请先配置: novel-system\python-services\.env
    pause
    exit /b 1
)
echo ✅ 配置文件存在

echo.
echo [3/4] 测试API配置...
echo.

cd novel-system\python-services
python test_api.py
if %errorlevel% neq 0 (
    echo.
    echo ❌ API配置测试失败
    echo 请检查.env文件中的API配置
    pause
    exit /b 1
)

cd ..\..

echo.
echo [4/4] 启动所有服务...
echo.

REM 创建日志目录
if not exist "logs" mkdir logs

echo 启动Python AI服务 (端口8000)...
start "Python AI服务" cmd /k "cd /d %PROJECT_ROOT%novel-system\python-services && uvicorn main:app --reload --host 0.0.0.0 --port 8000"
timeout /t 3 /nobreak >nul

echo 启动Java后端服务 (端口8080)...
start "Java后端服务" cmd /k "cd /d %PROJECT_ROOT%novel-system\backend && mvn spring-boot:run"
timeout /t 5 /nobreak >nul

echo 启动React前端 (端口5173)...
start "React前端" cmd /k "cd /d %PROJECT_ROOT%novel-system\frontend && npm run dev"

echo.
echo ╔══════════════════════════════════════════════════════╗
echo ║           所有服务启动中，请稍候...                  ║
echo ╚══════════════════════════════════════════════════════╝
echo.
echo 预计等待时间:
echo   - Python AI服务: 5秒
echo   - Java后端服务: 30-60秒 (首次启动需下载依赖)
echo   - React前端: 10-20秒
echo.
echo 启动完成后访问: http://localhost:5173
echo.
echo 服务地址:
echo   - 前端界面: http://localhost:5173
echo   - Java后端: http://localhost:8080
echo   - Python AI: http://localhost:8000
echo   - API文档: http://localhost:8000/docs
echo.
echo 按任意键打开前端页面...
pause >nul

start http://localhost:5173

echo.
echo ✅ 启动完成！
echo.
echo 如需停止服务，请关闭对应的命令行窗口
pause
