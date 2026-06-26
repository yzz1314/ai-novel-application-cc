# 第三方API配置指南

本系统使用 `litellm` 库，支持几乎所有主流的第三方API提供商。

---

## 🔧 配置步骤

### 1. 创建配置文件

在 `novel-system/python-services/` 目录下创建 `.env` 文件：

```bash
cd novel-system/python-services
touch .env
```

### 2. 配置环境变量

根据你使用的第三方API提供商，在 `.env` 文件中添加对应配置：

---

## 🌐 支持的第三方API提供商

### 选项1：OpenAI官方API

```env
# OpenAI官方
OPENAI_API_KEY=sk-your-openai-api-key
DEFAULT_MODEL=gpt-4
# 或使用 gpt-3.5-turbo 降低成本
# DEFAULT_MODEL=gpt-3.5-turbo
```

### 选项2：OpenAI兼容的第三方API（推荐）

大多数第三方提供商都提供OpenAI兼容接口，配置方式：

```env
# 第三方API配置
OPENAI_API_KEY=your-api-key-from-provider
OPENAI_API_BASE=https://api.your-provider.com/v1
DEFAULT_MODEL=gpt-4

# 示例：使用Azure OpenAI
# OPENAI_API_KEY=your-azure-key
# OPENAI_API_BASE=https://your-resource.openai.azure.com/
# OPENAI_API_VERSION=2023-05-15
# DEFAULT_MODEL=azure/your-deployment-name

# 示例：使用国内中转API
# OPENAI_API_KEY=your-key
# OPENAI_API_BASE=https://api.example.com/v1
# DEFAULT_MODEL=gpt-4
```

### 选项3：Anthropic Claude

```env
ANTHROPIC_API_KEY=your-anthropic-api-key
DEFAULT_MODEL=claude-3-opus-20240229
# 或使用其他版本
# DEFAULT_MODEL=claude-3-sonnet-20240229
# DEFAULT_MODEL=claude-3-haiku-20240307
```

### 选项4：通义千问（阿里云）

```env
QWEN_API_KEY=your-qwen-api-key
DEFAULT_MODEL=qwen-turbo
# 或其他模型
# DEFAULT_MODEL=qwen-plus
# DEFAULT_MODEL=qwen-max
```

### 选项5：文心一言（百度）

```env
WENXIN_API_KEY=your-api-key
WENXIN_SECRET_KEY=your-secret-key
DEFAULT_MODEL=ERNIE-Bot-4
# 或其他模型
# DEFAULT_MODEL=ERNIE-Bot
# DEFAULT_MODEL=ERNIE-Bot-turbo
```

### 选项6：智谱GLM（清华）

```env
ZHIPU_API_KEY=your-zhipu-api-key
DEFAULT_MODEL=glm-4
# 或其他模型
# DEFAULT_MODEL=glm-3-turbo
```

### 选项7：Moonshot（月之暗面）

```env
MOONSHOT_API_KEY=your-moonshot-api-key
DEFAULT_MODEL=moonshot-v1-8k
# 或其他模型
# DEFAULT_MODEL=moonshot-v1-32k
# DEFAULT_MODEL=moonshot-v1-128k
```

### 选项8：DeepSeek

```env
DEEPSEEK_API_KEY=your-deepseek-api-key
DEFAULT_MODEL=deepseek-chat
# 或其他模型
# DEFAULT_MODEL=deepseek-coder
```

---

## ⚙️ 完整配置示例

### 使用第三方OpenAI兼容API

```env
# .env 文件内容

# ============= API配置 =============
# 你的API密钥
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx

# API基础URL（第三方提供商会给你）
OPENAI_API_BASE=https://api.example.com/v1

# 使用的模型
DEFAULT_MODEL=gpt-4

# ============= 项目路径配置 =============
PROJECT_BASE_PATH=/workspace
SKILLS_PATH=/workspace/skills

# ============= 性能配置 =============
# 最大分块大小
MAX_CHUNK_SIZE=2500
# 分块重叠
CHUNK_OVERLAP=200
# 最大并发任务数
MAX_CONCURRENT_TASKS=3

# ============= 日志配置 =============
LOG_LEVEL=INFO

# ============= 可选：成本控制 =============
# 每日token限制
DAILY_TOKEN_LIMIT=1000000
# 每日成本限制（美元）
DAILY_COST_LIMIT=50.0
```

---

## 🚀 启动服务

### 1. 安装依赖

```bash
cd novel-system/python-services
pip install -r requirements.txt
```

### 2. 验证配置

创建测试脚本 `test_api.py`：

```python
import asyncio
from llm.client import LLMClient
from config import settings

async def test_api():
    print(f"测试模型: {settings.DEFAULT_MODEL}")
    
    client = LLMClient()
    
    try:
        result = await client.generate("你好，请回复'测试成功'")
        print(f"✅ API配置正确！")
        print(f"响应: {result['content']}")
        print(f"Token使用: {result['usage']}")
    except Exception as e:
        print(f"❌ API配置错误: {str(e)}")

if __name__ == "__main__":
    asyncio.run(test_api())
```

运行测试：

```bash
python test_api.py
```

### 3. 启动服务

```bash
# 开发模式
uvicorn main:app --reload --host 0.0.0.0 --port 8000

# 生产模式
uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
```

---

## 🔍 常见问题

### Q1: 如何确认配置是否生效？

查看启动日志，应该看到：
```
INFO: Loading environment variables from .env
INFO: Using model: gpt-4
INFO: API base URL: https://api.example.com/v1
```

### Q2: 如何切换不同的模型？

在 `.env` 文件中修改 `DEFAULT_MODEL`：

```env
# 使用GPT-4（质量高，成本高）
DEFAULT_MODEL=gpt-4

# 使用GPT-3.5（质量中，成本低）
DEFAULT_MODEL=gpt-3.5-turbo

# 使用Claude（质量高）
DEFAULT_MODEL=claude-3-opus-20240229
```

### Q3: 如何降低成本？

1. **使用更便宜的模型**：
   - GPT-4 → GPT-3.5-turbo（成本降低90%）
   - 只对关键任务使用GPT-4

2. **减少token使用**：
   ```env
   # 减小分块大小
   MAX_CHUNK_SIZE=2000
   
   # 减少并发
   MAX_CONCURRENT_TASKS=2
   ```

3. **添加缓存**：
   相同的prompt会使用缓存结果

### Q4: 如何处理API限流？

在 `.env` 中添加：

```env
# 限制并发请求
MAX_CONCURRENT_TASKS=2

# 或在代码中添加延迟
REQUEST_DELAY=1.0
```

### Q5: 支持多个API提供商吗？

可以！创建不同的配置文件：

```bash
# 开发环境
.env.development
OPENAI_API_KEY=dev-key
DEFAULT_MODEL=gpt-3.5-turbo

# 生产环境
.env.production
OPENAI_API_KEY=prod-key
DEFAULT_MODEL=gpt-4
```

启动时指定：
```bash
ENV_FILE=.env.production uvicorn main:app
```

---

## 📊 成本估算

### 单本书完整流程（100章）

使用 **GPT-3.5-turbo**（推荐）：
- 样本分析：约 $3
- Skills生成：约 $0.08
- 大纲生成：约 $0.24
- 正文创作：约 $4.8
- 记忆提取：约 $2.1
- **总计**：约 **$10-15/本**

使用 **GPT-4**（高质量）：
- 总计：约 **$100/本**

### 建议策略

混合使用：
- 样本分析、记忆提取：GPT-3.5-turbo
- 大纲生成、正文创作：GPT-4
- **总成本**：约 **$30-40/本**

---

## 🔒 安全建议

### 1. 保护API密钥

```bash
# .env 文件不要提交到Git
echo ".env" >> .gitignore
```

### 2. 使用环境变量

生产环境中使用系统环境变量：

```bash
export OPENAI_API_KEY=your-key
export OPENAI_API_BASE=https://api.example.com/v1
export DEFAULT_MODEL=gpt-4
```

### 3. 设置成本限制

```env
DAILY_COST_LIMIT=50.0
MONTHLY_COST_LIMIT=1000.0
```

---

## 📝 推荐配置（第三方API）

适合大多数用户的配置：

```env
# 第三方OpenAI兼容API
OPENAI_API_KEY=your-api-key
OPENAI_API_BASE=https://api.your-provider.com/v1

# 使用GPT-3.5降低成本
DEFAULT_MODEL=gpt-3.5-turbo

# 项目路径
PROJECT_BASE_PATH=/workspace
SKILLS_PATH=/workspace/skills

# 性能配置
MAX_CHUNK_SIZE=2500
CHUNK_OVERLAP=200
MAX_CONCURRENT_TASKS=3

# 日志
LOG_LEVEL=INFO

# 成本控制
DAILY_TOKEN_LIMIT=1000000
DAILY_COST_LIMIT=30.0
```

---

## ✅ 验证清单

配置完成后检查：

- [ ] `.env` 文件已创建
- [ ] API密钥已配置
- [ ] API基础URL已配置（如果使用第三方）
- [ ] 默认模型已设置
- [ ] 运行 `python test_api.py` 测试成功
- [ ] 启动服务无错误

---

**配置完成后，你的系统就可以使用第三方API了！** 🎉
