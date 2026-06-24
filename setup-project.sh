#!/bin/bash

# 小说创作系统项目骨架搭建脚本
# 创建完整的Monorepo结构

set -e

echo "🚀 开始创建项目骨架..."

# 1. 创建根目录结构
mkdir -p novel-system
cd novel-system

echo "📁 创建目录结构..."

# 创建主要目录
mkdir -p java-services
mkdir -p python-services
mkdir -p frontend
mkdir -p docker
mkdir -p docs
mkdir -p scripts
mkdir -p workspace/config
mkdir -p workspace/projects
mkdir -p workspace/builtin-skills
mkdir -p workspace/logs
mkdir -p workspace/tmp

echo "✅ 目录结构创建完成"

# 2. 创建根配置文件
echo "📝 创建根配置文件..."

# .gitignore
cat > .gitignore << 'GITIGNORE'
# IDE
.idea/
.vscode/
*.iml
*.swp
*.swo

# OS
.DS_Store
Thumbs.db

# Build
target/
build/
dist/
*.egg-info/
__pycache__/
*.pyc
*.pyo

# Dependencies
node_modules/
venv/
.env

# Workspace
workspace/projects/*/
workspace/logs/*.log
workspace/tmp/*
!workspace/projects/.gitkeep
!workspace/logs/.gitkeep
!workspace/tmp/.gitkeep

# Database
*.db
*.sqlite
*.sqlite3

# Secrets
.env.local
*.key
GITIGNORE

# .env.example
cat > .env.example << 'ENVEXAMPLE'
# Database
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=novel_system
POSTGRES_USER=novel_user
POSTGRES_PASSWORD=change_this_password

# Java Service
JAVA_SERVICE_PORT=8080
SPRING_PROFILES_ACTIVE=dev

# Python Service
PYTHON_SERVICE_PORT=8000
PYTHON_SERVICE_HOST=0.0.0.0

# Frontend
FRONTEND_PORT=3000
VITE_API_URL=http://localhost:8080

# LLM API Keys
OPENAI_API_KEY=sk-your-openai-key
ANTHROPIC_API_KEY=sk-ant-your-anthropic-key

# Application Settings
PROJECT_BASE_PATH=/workspace
MAX_FILE_SIZE_MB=20
MAX_CONCURRENT_TASKS=3
MAX_CHUNK_SIZE=2500
CHUNK_OVERLAP=200

# Logging
LOG_LEVEL=INFO
ENVEXAMPLE

# README.md
cat > README.md << 'README'
# 小说样本拆解与长篇创作系统

> 基于AI的小说创作辅助系统，支持样本拆解、大纲生成、正文创作

## 快速开始

### 前置要求

- Docker 20.10+
- Docker Compose 2.0+

### 启动步骤

1. 配置环境变量
```bash
cp .env.example .env
# 编辑 .env 文件，填入你的API密钥
```

2. 启动所有服务
```bash
docker-compose up -d
```

3. 访问服务
- 前端界面: http://localhost:3000
- Java API: http://localhost:8080
- Python API: http://localhost:8000
- API文档: http://localhost:8080/swagger-ui.html

### 停止服务

```bash
docker-compose down
```

### 查看日志

```bash
# 所有服务
docker-compose logs -f

# 特定服务
docker-compose logs -f java-service
docker-compose logs -f python-service
docker-compose logs -f frontend
```

## 项目结构

```
novel-system/
├── java-services/          # Java后端服务
├── python-services/        # Python AI服务
├── frontend/              # React前端
├── docker/                # Docker配置
├── workspace/             # 数据工作区
├── docs/                  # 文档
└── scripts/               # 工具脚本
```

## 开发指南

详见 [docs/](./docs/) 目录下的文档。

## 技术栈

- **后端**: Java 17 + Spring Boot 3.2
- **AI服务**: Python 3.11 + FastAPI
- **数据库**: PostgreSQL 15 + pgvector
- **前端**: React 18 + TypeScript + Vite
- **容器化**: Docker + Docker Compose

## 文档

- [系统架构设计](./docs/系统架构设计文档.md)
- [项目实现文档](./docs/项目实现文档.md)
- [架构补充内容](./docs/架构设计文档_补充内容.md)

## License

MIT
README

echo "✅ 根配置文件创建完成"

echo ""
echo "🎉 项目骨架创建完成！"
echo ""
echo "📍 项目位置: $(pwd)"
echo ""
echo "下一步:"
echo "1. cd novel-system"
echo "2. 继续执行后续脚本创建各服务骨架"

