# Docker镜像加速配置指南

## 问题：无法拉取镜像

错误信息：
```
failed to resolve reference "docker.io/library/redis:7-alpine"
```

这是因为无法访问Docker Hub，需要配置国内镜像源。

---

## 解决方案：配置镜像加速

### 方法1：通过Docker Desktop配置（推荐）

1. 打开 Docker Desktop
2. 点击右上角 **设置图标（齿轮）**
3. 选择 **Docker Engine**
4. 在JSON配置中添加：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com",
    "https://docker.mirrors.ustc.edu.cn",
    "https://docker.nju.edu.cn"
  ]
}
```

5. 点击 **Apply & Restart**

---

### 方法2：使用阿里云镜像加速

1. 访问：https://cr.console.aliyun.com/cn-hangzhou/instances/mirrors
2. 登录阿里云账号
3. 获取你的专属加速地址（类似：https://xxxxxx.mirror.aliyuncs.com）
4. 在Docker Desktop配置：

```json
{
  "registry-mirrors": [
    "https://xxxxxx.mirror.aliyuncs.com"
  ]
}
```

---

### 方法3：手动配置文件（备选）

如果Docker Desktop无法配置，可以手动编辑配置文件。

**Windows路径**：
```
C:\Users\你的用户名\.docker\daemon.json
```

创建或编辑该文件，添加：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com",
    "https://docker.mirrors.ustc.edu.cn"
  ]
}
```

保存后重启Docker Desktop。

---

## 验证配置

配置完成后验证：

```bash
docker info
```

查看输出中的 `Registry Mirrors` 部分，应该显示你配置的镜像源。

---

## 重新启动系统

配置完成后，重新构建和启动：

```bash
# 清理之前失败的容器
docker-compose down

# 重新拉取镜像
docker-compose pull

# 启动服务
docker-compose up -d
```

---

## 推荐的国内镜像源（2024年可用）

| 镜像源 | 地址 | 说明 |
|--------|------|------|
| DaoCloud | https://docker.m.daocloud.io | 稳定，推荐 |
| 南京大学 | https://docker.nju.edu.cn | 教育网快 |
| 中科大 | https://docker.mirrors.ustc.edu.cn | 稳定 |
| DockerProxy | https://dockerproxy.com | 备用 |

---

## 完整的Docker Engine配置示例

```json
{
  "builder": {
    "gc": {
      "defaultKeepStorage": "20GB",
      "enabled": true
    }
  },
  "experimental": false,
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com",
    "https://docker.mirrors.ustc.edu.cn",
    "https://docker.nju.edu.cn"
  ]
}
```

---

## 如果镜像加速仍然失败

### 备选方案：使用国内替代镜像

修改 `docker-compose.yml`：

```yaml
services:
  mysql:
    # image: mysql:8.0  # 原镜像
    image: registry.cn-hangzhou.aliyuncs.com/library/mysql:8.0  # 阿里云镜像
    
  redis:
    # image: redis:7-alpine  # 原镜像
    image: registry.cn-hangzhou.aliyuncs.com/library/redis:7-alpine  # 阿里云镜像
```

---

## 快速解决步骤

1. **打开Docker Desktop**
2. **设置 → Docker Engine**
3. **添加镜像源配置**
4. **Apply & Restart**
5. **重新运行**：
   ```bash
   docker-compose down
   docker-compose up -d
   ```

---

配置好镜像加速后，再次运行 `docker-compose up -d` 就可以正常下载镜像了！
