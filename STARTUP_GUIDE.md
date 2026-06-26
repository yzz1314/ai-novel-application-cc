# AI小说创作系统 - 启动指南

## 🚀 系统启动步骤

### 前置条件检查

✅ 已配置API密钥（.env文件）  
✅ MySQL已安装  
✅ Redis已安装（可选）  
✅ Java 17+已安装  
✅ Python 3.9+已安装  
✅ Node.js 16+已安装  

---

## 📋 启动顺序

### 1️⃣ 启动数据库服务

#### MySQL

**Windows**:
```cmd
# 如果MySQL作为服务运行
net start MySQL

# 或使用MySQL Workbench启动
```

**检查MySQL状态**:
```bash
mysql -u root -p
# 输入密码后看到mysql>提示符表示连接成功
```

**创建数据库**（首次启动需要）:
```sql
CREATE DATABASE novel_system CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
exit;
```

#### Redis（可选）

**Windows**:
```cmd
# 如果安装了Redis，启动服务
redis-server

# 或作为Windows服务
net start Redis
```

**检查Redis状态**:
```bash
redis-cli ping
# 返回 PONG 表示连接成功
```

---

### 2️⃣ 启动Python AI服务（端口8000）

```bash
# 进入Python服务目录
cd novel-system/python-services

# 创建.env文件（如果还没有）
copy .env.example .env

# 测试API配置
python test_api.py

# 启动服务
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

**预期输出**:
```
INFO:     Uvicorn running on http://0.0.0.0:8000 (Press CTRL+C to quit)
INFO:     Started reloader process
INFO:     Started server process
INFO:     Waiting for application startup.
INFO:     Application startup complete.
```

**测试访问**:
- 浏览器打开: http://localhost:8000/docs
- 应该看到FastAPI的交互式文档

---

### 3️⃣ 启动Java后端服务（端口8080）

```bash
# 进入Java后端目录
cd novel-system/backend

# 编译并启动
mvn spring-boot:run

# 或先编译再运行
mvn clean package
java -jar target/novel-system-backend-1.0.0.jar
```

**预期输出**:
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.x.x)

INFO: Started NovelSystemApplication in 5.234 seconds
INFO: Tomcat started on port(s): 8080 (http)
```

**测试访问**:
- 浏览器打开: http://localhost:8080/api/health
- 应该返回: `{"status":"UP"}`

---

### 4️⃣ 启动React前端（端口5173）

```bash
# 进入前端目录
cd novel-system/frontend

# 安装依赖（首次启动需要）
npm install

# 启动开发服务器
npm run dev
```

**预期输出**:
```
  VITE v5.0.11  ready in 1234 ms

  ➜  Local:   http://localhost:5173/
  ➜  Network: use --host to expose
  ➜  press h + enter to show help
```

**访问系统**:
- 浏览器打开: http://localhost:5173
- 应该看到AI小说创作系统的工作台页面

---

## ✅ 验证系统运行

### 检查所有服务状态

| 服务 | 端口 | 状态检查 |
|------|------|----------|
| MySQL | 3306 | `mysql -u root -p` |
| Redis | 6379 | `redis-cli ping` |
| Python AI服务 | 8000 | http://localhost:8000/docs |
| Java后端 | 8080 | http://localhost:8080/api/health |
| React前端 | 5173 | http://localhost:5173 |

### 完整流程测试

1. 打开前端: http://localhost:5173
2. 点击"创建项目"
3. 填写项目信息并保存
4. 如果成功，说明三个服务都正常通信

---

## 🐛 常见问题

### 问题1: Python服务启动失败

**错误**: `ModuleNotFoundError: No module named 'xxx'`

**解决**:
```bash
cd novel-system/python-services
pip install -r requirements.txt --break-system-packages
```

### 问题2: Java服务启动失败

**错误**: `Failed to configure a DataSource`

**解决**: 检查MySQL是否启动，数据库是否创建
```sql
CREATE DATABASE novel_system CHARACTER SET utf8mb4;
```

### 问题3: 前端无法连接后端

**错误**: `Network Error` 或 `CORS Error`

**解决**: 检查Java后端是否运行在8080端口
```bash
# 检查端口
netstat -ano | findstr :8080
```

### 问题4: API调用失败

**错误**: `API key is invalid`

**解决**: 检查.env文件配置
```bash
cd novel-system/python-services
cat .env
# 确认OPENAI_API_KEY和OPENAI_API_BASE正确
```

### 问题5: 端口被占用

**错误**: `Address already in use`

**解决**: 更改端口或关闭占用进程
```bash
# Windows查找占用端口的进程
netstat -ano | findstr :8000
# 结束进程
taskkill /F /PID [进程ID]
```

---

## 🔧 开发模式 vs 生产模式

### 开发模式（当前）

- Python: `uvicorn main:app --reload`
- Java: `mvn spring-boot:run`
- React: `npm run dev`

**特点**: 热重载、详细日志、方便调试

### 生产模式

**Python**:
```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
```

**Java**:
```bash
java -jar target/novel-system-backend-1.0.0.jar --spring.profiles.active=prod
```

**React**:
```bash
npm run build
# 使用nginx或其他服务器部署dist目录
```

---

## 📊 系统架构

```
浏览器 (http://localhost:5173)
    ↓
React前端 (Vite开发服务器)
    ↓ HTTP请求
Java后端 (Spring Boot, :8080)
    ↓ HTTP请求
Python AI服务 (FastAPI, :8000)
    ↓
LLM API (第三方)
    
数据存储:
├─ MySQL (项目、样本、任务)
├─ Redis (缓存、会话)
└─ 文件系统 (Skills、大纲、正文)
```

---

## 🎯 下一步

系统启动成功后，你可以:

1. **创建项目** - 在前端创建第一个项目
2. **上传样本** - 上传2-3本TXT格式的样本小说
3. **分析样本** - 等待自动分析完成
4. **生成Skills** - 系统自动生成创作指导
5. **规划大纲** - 输入创作需求生成大纲
6. **创作正文** - 开始AI辅助创作

---

## 📝 快速命令参考

```bash
# 启动所有服务（需要3个终端窗口）

# 终端1: Python AI服务
cd novel-system/python-services && uvicorn main:app --reload

# 终端2: Java后端
cd novel-system/backend && mvn spring-boot:run

# 终端3: React前端
cd novel-system/frontend && npm run dev
```

---

**系统启动完成！访问 http://localhost:5173 开始使用！** 🎉
