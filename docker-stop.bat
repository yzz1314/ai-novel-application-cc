@echo off
chcp 65001 >nul
echo 🛑 停止所有服务...
cd /d %~dp0
docker-compose stop
echo ✅ 所有服务已停止
pause
