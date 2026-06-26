# AI小说创作系统 - Docker部署指南

## 🐳 Docker化部署

完整的容器化部署方案，一键启动所有服务。

---

## 📋 前置要求

### 必需软件

- ✅ **Docker Desktop** 20.10+
- ✅ **Docker Compose** 2.0+

### 安装Docker Desktop（Windows）

1. 下载：https://www.docker.com/products/docker-desktop
2. 安装并启动Docker Desktop
3. 确认安装：
   ```bash
   docker --version
   docker-compose --version
   ```

---

## 🚀 快速开始（3步）

### 第1步：配置环境变量

创建Python服务的配置文件：

```bash
cd novel-system/python-services
copy .env.example .env
```

编辑 `.env` 文件，填入你的API配置：

```env
OPENAI_API_KEY=sk-your-api-key
OPENAI_API_BASE=https://subapi.xiaoye.lol
DEFAULT_MODEL=gpt-3.5-turbo
```

### 第2步：构建镜像

```bash
# 回到项目根目录
cd D:\code\ai-novel-application-cc

# 构建所有服务镜像
docker-compose build
```

**预计时间**：首次构建约5-10分钟

### 第3步：启动服务

```bash
# 启动所有服务
docker-compose up -d
```

**就这么简单！** 🎉

---

## 🌐 访问服务

启动成功后，访问：

| 服务 | 地址 | 说明 |
|------|------|------|
| **前端界面** | http://localhost | 主界面 |
| Java后端 | http://localhost:8080 | REST API |
| Python AI | http://localhost:8000 | AI服务 |
| API文档 | http://localhost:8000/docs | FastAPI文档 |
| MySQL | localhost:3306 | 数据库 |
| Redis | localhost:6379 | 缓存 |

---

## 📊 Docker架构

```
┌─────────────────────────────────────────────────┐
│              Docker Network                      │
├─────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────┐      ┌──────────────┐        │
│  │   Frontend   │      │ Java Backend │        │
│  │  (Nginx)     │─────▶│ (Spring Boot)│        │
│  │   Port: 80   │      │  Port: 8080  │        │
│  └──────────────┘      └──────┬───────┘        │
│                                │                 │
│                         ┌──────▼───────┐        │
│                         │  Python AI   │        │
│                         │  (FastAPI)   │        │
│                         │  Port: 8000  │        │
│                         └──────────────┘        │
│                                                  │
│  ┌──────────────┐      ┌──────────────┐        │
│  │    MySQL     │      │    Redis     │        │
│  │  Port: 3306  │      │  Port: 6379  │        │
│  └──────────────┘      └──────────────┘        │
│                                                  │
└─────────────────────────────────────────────────┘
```

---

## 🔧 常用命令

### 启动服务

```bash
# 启动所有服务（后台运行）
docker-compose up -d

# 启动所有服务（前台运行，查看日志）
docker-compose up

# 启动指定服务
docker-compose up -d mysql redis python-ai
```

### 停止服务

```bash
# 停止所有服务
docker-compose stop

# 停止并删除容器
docker-compose down

# 停止并删除容器+数据卷
docker-compose down -v
```

### 查看状态

```bash
# 查看所有服务状态
docker-compose ps

# 查看服务日志
docker-compose logs

# 查看指定服务日志
docker-compose logs -f python-ai
docker-compose logs -f java-backend
docker-compose logs -f frontend

# 实时查看日志
docker-compose logs -f --tail=100
```

### 重启服务

```bash
# 重启所有服务
docker-compose restart

# 重启指定服务
docker-compose restart python-ai
docker-compose restart java-backend
```

### 进入容器

```bash
# 进入Python容器
docker exec -it novel-python-ai bash

# 进入Java容器
docker exec -it novel-java-backend sh

# 进入MySQL容器
docker exec -it novel-mysql mysql -u root -p
```

### 查看资源使用

```bash
# 查看容器资源使用情况
docker stats

# 查看磁盘使用
docker system df
```

---

## 🔍 健康检查

所有服务都配置了健康检查，可以查看状态：

```bash
# 查看健康状态
docker-compose ps

# 应该看到：
# novel-mysql        Up (healthy)
# novel-redis        Up (healthy)
# novel-python-ai    Up (healthy)
# novel-java-backend Up (healthy)
# novel-frontend     Up (healthy)
```

---

## 📝 环境变量配置

### Python AI服务（.env）

```env
# API配置
OPENAI_API_KEY=your-api-key
OPENAI_API_BASE=https://api.example.com/v1
DEFAULT_MODEL=gpt-3.5-turbo

# 项目路径（容器内路径）
PROJECT_BASE_PATH=/workspace
SKILLS_PATH=/workspace/skills

# 性能配置
MAX_CHUNK_SIZE=2500
CHUNK_OVERLAP=200
MAX_CONCURRENT_TASKS=3

# 日志
LOG_LEVEL=INFO
```

### Java后端（通过docker-compose.yml配置）

数据库和Redis连接已自动配置，无需手动设置。

---

## 🗄️ 数据持久化

系统使用Docker volumes持久化数据：

```bash
# 查看数据卷
docker volume ls

# 应该看到：
# ai-novel-application-cc_mysql-data
# ai-novel-application-cc_redis-data
# ai-novel-application-cc_workspace-data
```

### 备份数据

```bash
# 备份MySQL数据
docker exec novel-mysql mysqldump -u root -proot123456 novel_system > backup.sql

# 备份workspace数据
docker cp novel-python-ai:/workspace ./workspace-backup
```

### 恢复数据

```bash
# 恢复MySQL数据
docker exec -i novel-mysql mysql -u root -proot123456 novel_system < backup.sql

# 恢复workspace数据
docker cp ./workspace-backup novel-python-ai:/workspace
```

---

## 🐛 故障排查

### 问题1：服务启动失败

**查看日志**：
```bash
docker-compose logs python-ai
docker-compose logs java-backend
```

**常见原因**：
- API配置错误：检查 `.env` 文件
- 端口被占用：修改 `docker-compose.yml` 中的端口映射
- 内存不足：增加Docker Desktop内存限制

### 问题2：无法连接数据库

**检查MySQL状态**：
```bash
docker-compose ps mysql
docker-compose logs mysql
```

**手动连接测试**：
```bash
docker exec -it novel-mysql mysql -u root -proot123456
```

### 问题3：前端显示网络错误

**检查后端状态**：
```bash
docker-compose ps java-backend
docker-compose logs java-backend
```

**检查网络连接**：
```bash
docker network inspect ai-novel-application-cc_novel-network
```

### 问题4：Python AI服务报错

**进入容器检查**：
```bash
docker exec -it novel-python-ai bash
python test_api.py
```

### 问题5：构建失败

**清理并重新构建**：
```bash
docker-compose down -v
docker system prune -a
docker-compose build --no-cache
docker-compose up -d
```

---

## 🔄 更新服务

### 更新代码后重新部署

```bash
# 停止服务
docker-compose down

# 重新构建
docker-compose build

# 启动服务
docker-compose up -d
```

### 仅更新某个服务

```bash
# 重新构建并重启Python服务
docker-compose up -d --build python-ai

# 重新构建并重启Java服务
docker-compose up -d --build java-backend
```

---

## 🚀 生产环境部署

### 优化配置

编辑 `docker-compose.yml`：

```yaml
services:
  python-ai:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '1'
          memory: 1G
```

### 使用外部数据库

修改 `docker-compose.yml`，移除MySQL和Redis服务，配置外部连接：

```yaml
services:
  java-backend:
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://your-db-host:3306/novel_system
      SPRING_DATASOURCE_USERNAME: your-username
      SPRING_DATASOURCE_PASSWORD: your-password
```

---

## 📊 监控和日志

### 查看实时日志

```bash
# 所有服务
docker-compose logs -f

# 只看错误
docker-compose logs -f | grep ERROR

# 指定服务
docker-compose logs -f python-ai
```

### 导出日志

```bash
# 导出到文件
docker-compose logs > logs/docker-$(date +%Y%m%d).log
```

---

## 🎯 完整启动流程

### 首次部署

```bash
# 1. 进入项目目录
cd D:\code\ai-novel-application-cc

# 2. 配置环境变量
cd novel-system/python-services
copy .env.example .env
# 编辑.env文件，填入API配置
cd ../..

# 3. 构建镜像
docker-compose build

# 4. 启动服务
docker-compose up -d

# 5. 查看状态
docker-compose ps

# 6. 查看日志
docker-compose logs -f

# 7. 访问系统
# 浏览器打开 http://localhost
```

### 日常使用

```bash
# 启动
docker-compose up -d

# 停止
docker-compose stop

# 查看日志
docker-compose logs -f
```

---

## ✅ 验证部署

### 1. 检查容器状态

```bash
docker-compose ps
```

所有服务应该显示 `Up (healthy)`

### 2. 测试API

```bash
# 测试Python AI服务
curl http://localhost:8000/health

# 测试Java后端
curl http://localhost:8080/actuator/health
```

### 3. 访问前端

浏览器打开 http://localhost

应该看到系统工作台页面

---

## 🎉 完成！

Docker部署完成后：

- ✅ 所有服务容器化运行
- ✅ 数据自动持久化
- ✅ 服务自动重启
- ✅ 健康检查自动化
- ✅ 网络隔离和安全

---

## 📝 常用脚本

创建便捷脚本（Windows）：

**启动脚本 `docker-start.bat`**：
```batch
@echo off
cd /d D:\code\ai-novel-application-cc
docker-compose up -d
docker-compose logs -f
```

**停止脚本 `docker-stop.bat`**：
```batch
@echo off
cd /d D:\code\ai-novel-application-cc
docker-compose stop
```

**重启脚本 `docker-restart.bat`**：
```batch
@echo off
cd /d D:\code\ai-novel-application-cc
docker-compose restart
docker-compose logs -f
```

---

**Docker部署配置完成！运行 `docker-compose up -d` 启动系统！** 🐳
