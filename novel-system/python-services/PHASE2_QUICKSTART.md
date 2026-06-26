# Phase 2 快速入门指南

## 环境准备

### 1. 安装Python依赖

```bash
cd novel-system/python-services
pip install -r requirements.txt
```

### 2. 配置环境变量

创建 `.env` 文件：

```bash
# LLM API密钥（至少配置一个）
OPENAI_API_KEY=your_openai_api_key
ANTHROPIC_API_KEY=your_anthropic_api_key

# 项目路径
PROJECT_BASE_PATH=/workspace
SKILLS_PATH=/workspace/skills

# 模型配置
DEFAULT_MODEL=gpt-4
MAX_CHUNK_SIZE=2500
CHUNK_OVERLAP=200

# 并发配置
MAX_CONCURRENT_TASKS=3

# 日志级别
LOG_LEVEL=INFO
```

### 3. 创建工作目录

```bash
mkdir -p /workspace/projects
mkdir -p /workspace/skills
```

## 启动服务

### 启动Python服务

```bash
cd novel-system/python-services
uvicorn main:app --host 0.0.0.0 --port 8001 --reload
```

访问：http://localhost:8001/docs 查看API文档

### 启动Java服务

```bash
cd novel-system/java-services
./mvnw spring-boot:run
```

访问：http://localhost:8080

## 使用流程

### 1. 创建项目

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-project",
    "description": "测试项目"
  }'
```

返回：`project_id`

### 2. 上传样本

```bash
curl -X POST http://localhost:8080/api/projects/{project_id}/samples/upload \
  -F "file=@sample.txt" \
  -F "metadata={\"title\":\"样本小说\"}"
```

返回：`sample_id`

### 3. 导入样本（样本分析第1步）

```bash
curl -X POST http://localhost:8080/api/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "{project_id}",
    "agentName": "sample_import",
    "inputRefs": {
      "sample_id": "{sample_id}"
    }
  }'
```

结果：
- 规范化文本
- 章节识别
- 文本分块
- 覆盖率验证

### 4. 全文分析（样本分析第2步）

```bash
curl -X POST http://localhost:8080/api/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "{project_id}",
    "agentName": "full_text_analysis",
    "inputRefs": {
      "sample_id": "{sample_id}"
    },
    "config": {
      "skill_name": "your_skill_name"  # 可选
    }
  }'
```

结果：
- 每个chunk的详细分析
- 技巧提取
- 分析摘要

### 5. 单书汇总（样本分析第3步）

```bash
curl -X POST http://localhost:8080/api/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "{project_id}",
    "agentName": "book_summary",
    "inputRefs": {
      "sample_id": "{sample_id}"
    }
  }'
```

结果：
- Markdown格式的分析报告
- 包含作者风格、情节设计、可迁移技巧等

### 6. 跨书归纳（多本样本完成后）

```bash
curl -X POST http://localhost:8080/api/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "{project_id}",
    "agentName": "cross_book_synthesis",
    "inputRefs": {
      "sample_ids": ["{sample_id_1}", "{sample_id_2}", "{sample_id_3}"]
    }
  }'
```

结果：
- 跨书技巧归纳报告
- 技巧频率统计
- 可迁移技巧矩阵
- 项目专属Skill建议

## 查看结果

### 项目目录结构

```
/workspace/projects/{project_id}/
├── samples/
│   ├── raw/              # 原始文件
│   ├── normalized/       # 规范化文本
│   ├── chunks/          # 分块结果
│   │   └── {sample_id}/
│   ├── manifests/       # 元数据
│   ├── analysis/        # 分析结果
│   │   └── {sample_id}/
│   │       ├── chunk_*.json    # 每个chunk的分析
│   │       └── summary.json    # 分析摘要
│   └── reports/         # 生成的报告
│       └── {sample_id}_report.md
├── cross_book_synthesis.md    # 跨书归纳报告
└── technique_summary.json     # 技巧汇总
```

## 直接调用Python API（开发/调试）

### 列出所有Agent

```bash
curl http://localhost:8001/api/agents/list
```

### 执行Agent

```bash
curl -X POST http://localhost:8001/api/agents/full_text_analysis/run \
  -H "Content-Type: application/json" \
  -d '{
    "task_id": "test-task-1",
    "project_id": "test-project",
    "input_refs": {
      "sample_id": "sample-1"
    },
    "config": {}
  }'
```

## Skill使用

### 创建自定义Skill

在 `/workspace/skills/` 目录下创建 `my_skill.md`：

```markdown
---
title: 我的自定义Skill
description: 专注于特定类型小说的分析
version: 1.0.0
---

# 我的自定义Skill

## 分析目标

专注分析以下方面...

## 分析维度

### 文笔技巧
...

### 情节技巧
...
```

### 使用Skill

在全文分析时指定：

```json
{
  "config": {
    "skill_name": "my_skill"
  }
}
```

## 常见问题

### Q1: LLM调用失败

**原因**：API密钥未配置或无效

**解决**：
1. 检查 `.env` 文件
2. 确认API密钥有效
3. 检查网络连接

### Q2: 分析结果不理想

**原因**：Prompt不够精确或模型能力限制

**解决**：
1. 创建自定义Skill
2. 调整Prompt模板
3. 尝试更强大的模型（如GPT-4）

### Q3: 内存不足

**原因**：样本太大或分块太多

**解决**：
1. 减小MAX_CHUNK_SIZE
2. 增加CHUNK_OVERLAP
3. 分批处理

### Q4: 分析速度慢

**原因**：LLM API调用是同步的

**解决**：
1. 使用更快的模型
2. 增加MAX_CONCURRENT_TASKS（需要修改代码支持并发）
3. 考虑使用本地模型

## 开发与扩展

### 添加新Agent

1. 创建Agent类（继承BaseAgent）
2. 实现 `run()` 方法
3. 在 `agents/__init__.py` 中导出
4. 在 `agent_routes.py` 中注册

### 自定义Prompt

修改 `llm/prompt_builder.py` 中的Prompt模板

### 添加新的分析维度

修改 `build_chunk_analysis_prompt()` 的JSON schema

## 监控与调试

### 查看日志

```bash
# Python服务日志
tail -f logs/python-service.log

# Java服务日志  
tail -f logs/java-service.log
```

### 监控LLM使用

每个Agent响应都包含metrics：

```json
{
  "metrics": {
    "llm_calls": 10,
    "input_tokens": 25000,
    "output_tokens": 5000,
    "duration_ms": 30000
  }
}
```

### 调试模式

设置 `LOG_LEVEL=DEBUG` 查看详细日志

## 性能优化建议

1. **使用缓存**：相同内容不重复分析
2. **批量处理**：一次性分析多个chunk
3. **降采样**：对超长文本可以跳过部分chunk
4. **模型选择**：根据任务复杂度选择合适的模型
5. **并发控制**：避免超出API限流

## 成本估算

假设使用GPT-4：
- 样本大小：200,000字
- Chunk大小：2,500字
- Chunk数量：80个
- 每个Chunk分析：约4,000 input tokens + 1,000 output tokens

**总成本估算**：
- Input: 80 × 4,000 = 320,000 tokens ≈ $9.6
- Output: 80 × 1,000 = 80,000 tokens ≈ $4.8
- **单本样本总计**：约 $14.4

建议：
- 使用GPT-3.5进行初步分析（成本降低90%）
- 只对关键章节使用GPT-4
- 设置预算上限

---

**问题反馈**：如有问题请查看 `PHASE2_COMPLETION_REPORT.md`
