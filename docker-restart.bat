@echo off
chcp 65001 >nul
echo 🔄 重启所有服务...
cd /d %~dp0
docker-compose restart
echo ✅ 所有服务已重启
docker-compose logs -f
