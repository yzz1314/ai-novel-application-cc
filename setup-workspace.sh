#!/bin/bash

# Workspace初始化脚本

set -e

echo "📦 初始化Workspace..."

cd novel-system/workspace

# 创建.gitkeep文件
touch projects/.gitkeep
touch logs/.gitkeep
touch tmp/.gitkeep

# config/app.yaml
cat > config/app.yaml << 'YAML'
# 应用配置
app:
  name: "Novel System"
  version: "1.0.0"
  
# 默认配置
defaults:
  chunk_size: 2500
  chunk_overlap: 200
  single_chapter_words: 3000
  recent_chapter_window: 3
YAML

# builtin-skills/sample_deconstruction.skill.md
cat > builtin-skills/sample_deconstruction.skill.md << 'SKILL'
---
skill_id: sample_deconstruction
skill_name: 样本拆解Skill
scope: builtin
stage: sample_analysis
priority: 80
version: "1.0"
---

# 样本拆解Skill

## 目标

对小说样本进行全方位拆解，提取可复用的写作技巧。

## 分析维度

### 1. 情节功能
- 本段落在整体叙事中的作用
- 推进了什么情节
- 引入了什么信息

### 2. 冲突设计
- 外部冲突
- 内部冲突
- 社会冲突

### 3. 人物塑造
- 人物状态
- 状态变化
- 塑造手法

### 4. 场景技巧
- 场景类型（战斗/日常/情感）
- 使用的技巧
- 效果评估

### 5. 文笔手法
- 句式特点
- 修辞手法
- 描写方式

### 6. 节奏控制
- 快慢节奏
- 信息密度
- 转场方式

### 7. 爽点设计
- 爽点类型
- 铺垫方式
- 强度评估

### 8. 悬念伏笔
- 埋设方式
- 涉及元素
- 预期回收

## 输出格式

以JSON格式输出，包含以上所有维度的分析结果。
SKILL

# builtin-skills/chapter_writing.skill.md
cat > builtin-skills/chapter_writing.skill.md << 'SKILL'
---
skill_id: builtin_chapter_writing
skill_name: 系统内置正文创作Skill
scope: builtin
stage: chapter_writing
priority: 90
version: "1.0"
---

# 系统内置正文创作Skill

## 基本原则

### 1. 完成目标
- 严格按照章节大纲创作
- 完成must_write内容
- 不越界到后续章节

### 2. 保持一致
- 人物设定一致
- 世界观一致
- 时间线一致

### 3. 节奏控制
- 每1000字有一个信息点或冲突点
- 避免连续500字纯描写
- 保持读者兴趣

### 4. 章末钩子
- 必须有章末钩子
- 类型：转折/威胁/悬念
- 引发读者期待

## 禁止事项

- 禁止人设崩坏
- 禁止无铺垫突破
- 禁止提前完成后续章纲
- 禁止违背Canon规则

## 质量标准

- 字数：2500-3500字
- 结构完整
- 逻辑连贯
- 有阅读体验
SKILL

echo "✅ Workspace初始化完成"

