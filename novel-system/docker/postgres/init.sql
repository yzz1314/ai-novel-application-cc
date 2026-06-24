-- 初始化数据库脚本

-- 启用pgvector扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 创建schema
CREATE SCHEMA IF NOT EXISTS novel_system;

-- 设置默认schema
SET search_path TO novel_system, public;

-- 初始化完成
SELECT 'Database initialized successfully' AS status;
