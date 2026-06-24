# 🚀 快速启动指南

## 前置要求

- **Docker**: 20.10+
- **Docker Compose**: 2.0+
- **磁盘空间**: 至少 5GB

## 一键构建骨架

```bash
# 1. 进入工作目录
cd /Users/zhenyang/Julian/personal/code/ai-novel-application

# 2. 执行构建脚本
./build-all.sh
```

这个脚本会自动创建完整的项目骨架，包括：
- ✅ Java后端服务
- ✅ Python AI服务
- ✅ React前端
- ✅ Docker配置
- ✅ Workspace工作区
- ✅ 技术文档

## 启动系统

```bash
# 1. 进入项目目录
cd novel-system

# 2. 配置API密钥（重要！）
vim .env

# 修改以下配置：
# OPENAI_API_KEY=sk-your-real-openai-key
# ANTHROPIC_API_KEY=sk-ant-your-real-anthropic-key
# POSTGRES_PASSWORD=your-secure-password

# 3. 启动所有服务
docker-compose up -d

# 4. 等待服务启动（首次启动需要下载镜像和构建，约5-10分钟）
# 查看启动状态
docker-compose ps

# 查看日志
docker-compose logs -f
```

## 验证服务

```bash
# 检查所有服务是否正常运行
curl http://localhost:8080/api/health    # Java服务
curl http://localhost:8000/health        # Python服务
curl http://localhost:3000               # 前端服务
```

## 访问系统

在浏览器中打开：

- **前端界面**: http://localhost:3000
- **Java API文档**: http://localhost:8080/swagger-ui.html
- **Python API文档**: http://localhost:8000/docs

## 常用命令

```bash
# 查看运行状态
docker-compose ps

# 查看所有服务日志
docker-compose logs -f

# 查看特定服务日志
docker-compose logs -f java-service
docker-compose logs -f python-service
docker-compose logs -f frontend

# 重启服务
docker-compose restart

# 停止服务
docker-compose stop

# 停止并删除容器（保留数据）
docker-compose down

# 停止并删除所有内容（包括数据）
docker-compose down -v

# 重新构建服务
docker-compose build --no-cache

# 重新构建并启动
docker-compose up -d --build
```

## 项目结构

```
novel-system/
├── docker-compose.yml          # Docker编排配置
├── .env                        # 环境变量配置
├── java-services/             # Java后端服务
│   ├── src/                   # 源代码
│   ├── pom.xml               # Maven配置
│   └── Dockerfile            # Docker镜像
├── python-services/          # Python AI服务
│   ├── api/                  # API路由
│   ├── agents/               # Agent实现
│   ├── requirements.txt      # Python依赖
│   └── Dockerfile           # Docker镜像
├── frontend/                # React前端
│   ├── src/                 # 源代码
│   ├── package.json        # NPM配置
│   └── Dockerfile          # Docker镜像
├── workspace/              # 数据工作区
│   ├── projects/          # 项目数据
│   ├── config/           # 配置文件
│   ├── builtin-skills/   # 内置Skills
│   └── logs/            # 日志文件
└── docs/               # 技术文档
    ├── 系统架构设计文档.md
    ├── 项目实现文档.md
    ├── 架构设计文档_补充内容.md
    └── 文档总览.md
```

## 开发模式

如果你想在本地开发而不是用Docker：

### Java服务

```bash
cd java-services

# 启动PostgreSQL（使用Docker）
docker run -d \
  --name postgres \
  -e POSTGRES_DB=novel_system \
  -e POSTGRES_USER=novel_user \
  -e POSTGRES_PASSWORD=password \
  -p 5432:5432 \
  pgvector/pgvector:pg15

# 启动Java服务
mvn spring-boot:run
```

### Python服务

```bash
cd python-services

# 创建虚拟环境
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# 安装依赖
pip install -r requirements.txt

# 启动服务
python main.py
```

### 前端

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

## 故障排查

### 服务启动失败

```bash
# 查看详细日志
docker-compose logs java-service
docker-compose logs python-service

# 检查端口占用
lsof -i :8080  # Java服务端口
lsof -i :8000  # Python服务端口
lsof -i :3000  # 前端端口
lsof -i :5432  # PostgreSQL端口
```

### 数据库连接失败

```bash
# 检查PostgreSQL是否运行
docker-compose ps postgres

# 进入PostgreSQL容器
docker-compose exec postgres psql -U novel_user -d novel_system

# 检查扩展
\dx
```

### 前端无法访问API

检查 `frontend/.env` 或 `docker-compose.yml` 中的 `VITE_API_URL` 配置。

### Python服务依赖安装失败

```bash
# 重新构建Python镜像
docker-compose build --no-cache python-service
```

## 下一步

1. 阅读技术文档：`docs/文档总览.md`
2. 根据实现文档开始编码
3. 参考架构补充内容了解核心机制
4. 使用提供的骨架直接开发

## 帮助

- 技术文档：`docs/` 目录
- 问题反馈：创建 Issue
- API文档：访问 `/swagger-ui.html` 和 `/docs`

---

祝开发顺利！🎉
