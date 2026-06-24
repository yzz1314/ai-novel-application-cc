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
