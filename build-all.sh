#!/bin/bash

# 一键构建所有服务骨架的主脚本

set -e

echo "╔════════════════════════════════════════════════════════════╗"
echo "║   小说样本拆解与长篇创作系统 - 项目骨架构建工具           ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# 检查当前目录
if [ -d "novel-system" ]; then
    echo "⚠️  警告: novel-system 目录已存在"
    read -p "是否删除并重新创建? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "🗑️  删除现有目录..."
        rm -rf novel-system
    else
        echo "❌ 已取消"
        exit 1
    fi
fi

echo ""
echo "开始构建项目骨架..."
echo ""

# 步骤1: 创建基础项目结构
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "步骤 1/6: 创建基础项目结构"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
./setup-project.sh
echo ""

# 步骤2: 创建Docker配置
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "步骤 2/6: 创建Docker配置"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
./setup-docker.sh
echo ""

# 步骤3: 创建Java服务骨架
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "步骤 3/6: 创建Java服务骨架"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
./setup-java-service.sh
echo ""

# 步骤4: 创建Python服务骨架
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "步骤 4/6: 创建Python服务骨架"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
./setup-python-service.sh
echo ""

# 步骤5: 创建前端骨架
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "步骤 5/6: 创建前端骨架"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
./setup-frontend.sh
echo ""

# 步骤6: 初始化Workspace
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "步骤 6/6: 初始化Workspace"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
./setup-workspace.sh
echo ""

# 复制文档
echo "📚 复制技术文档..."
cp 系统架构设计文档.md novel-system/docs/
cp 项目实现文档.md novel-system/docs/
cp 架构设计文档_补充内容.md novel-system/docs/
cp 文档总览.md novel-system/docs/
echo ""

# 创建.env文件
echo "🔐 创建环境配置文件..."
cd novel-system
cp .env.example .env
echo ""

# 显示项目结构
echo "╔════════════════════════════════════════════════════════════╗"
echo "║                     ✅ 构建完成！                          ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "📁 项目结构："
echo ""
tree -L 2 -I 'node_modules|target|__pycache__|.git' || ls -la
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 下一步操作："
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. 进入项目目录:"
echo "   cd novel-system"
echo ""
echo "2. 配置环境变量:"
echo "   vim .env"
echo "   # 填入你的 OPENAI_API_KEY 和 ANTHROPIC_API_KEY"
echo ""
echo "3. 启动所有服务:"
echo "   docker-compose up -d"
echo ""
echo "4. 查看日志:"
echo "   docker-compose logs -f"
echo ""
echo "5. 访问服务:"
echo "   - 前端: http://localhost:3000"
echo "   - Java API: http://localhost:8080"
echo "   - Python API: http://localhost:8000"
echo "   - API文档: http://localhost:8080/swagger-ui.html"
echo ""
echo "6. 停止服务:"
echo "   docker-compose down"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📖 文档位置: docs/"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

