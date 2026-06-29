"""
Prompt构建器
构建各种分析任务的prompt
"""
from typing import Dict, Any

class PromptBuilder:
    """Prompt构建器"""

    def build_chunk_analysis_prompt(self, chunk_text: str,
                                   chapter_range: str,
                                   skill_content: str = "") -> str:
        """构建分块分析prompt"""

        prompt = f"""
你是一位专业的小说分析专家。请对以下小说片段进行详细拆解。

## 分析目标

{skill_content if skill_content else self._get_default_analysis_guidelines()}

## 待分析片段

章节范围：{chapter_range}

内容：
{chunk_text}

## 输出要求

以JSON格式返回，包含以下字段：

{{
  "summary": "片段摘要（100字以内）",
  "plot_function": "情节功能描述",
  "reader_hook": "读者钩子描述",
  "conflict": {{
    "external": "外部冲突描述",
    "internal": "内部冲突描述",
    "social": "社会冲突描述"
  }},
  "characters": [
    {{
      "name": "角色名称",
      "role_in_chunk": "在本段的作用",
      "state_change": "状态变化"
    }}
  ],
  "scene_techniques": [
    {{
      "scene_type": "场景类型（战斗/日常/情感）",
      "technique": "使用的技巧",
      "evidence_location": "证据位置（字数范围）"
    }}
  ],
  "prose_techniques": [
    {{
      "technique_type": "文笔技巧类型",
      "description": "技巧描述",
      "evidence_location": "证据位置"
    }}
  ],
  "outline_techniques": [
    {{
      "technique_type": "大纲技巧类型",
      "description": "技巧描述"
    }}
  ],
  "suspense_and_foreshadowing": [
    {{
      "type": "悬念或伏笔",
      "description": "描述",
      "related_entities": ["相关实体"]
    }}
  ],
  "appeal_points": [
    {{
      "type": "爽点类型",
      "description": "描述",
      "intensity": "强度（高/中/低）"
    }}
  ]
}}
"""
        return prompt

    def _get_default_analysis_guidelines(self) -> str:
        """默认的分析指南"""
        return """
1. **情节功能**：本段落在整体叙事中的作用
2. **冲突设计**：外部冲突、内部冲突、社会冲突
3. **人物塑造**：人物状态、状态变化、塑造手法
4. **场景技巧**：场景类型、使用的技巧、效果
5. **文笔手法**：句式特点、修辞手法、描写方式
6. **大纲技巧**：节奏控制、信息密度、转场方式
7. **爽点设计**：爽点类型、铺垫方式、强度
8. **悬念伏笔**：埋设方式、涉及元素、预期回收
"""

    def build_book_summary_prompt(self, analysis_results: list,
                                  book_info: Dict) -> str:
        """构建单书汇总prompt"""

        # 汇总所有分析结果的关键信息
        summaries = []
        for i, result in enumerate(analysis_results[:10], 1):  # 只取前10个作为示例
            analysis = result.get("analysis") if isinstance(result, dict) else {}
            summary = result.get("summary") if isinstance(result, dict) else None
            if not summary and isinstance(analysis, dict):
                summary = analysis.get("summary")
            summaries.append(f"{i}. {summary or 'N/A'}")

        prompt = f"""
你是一位专业的小说分析师。请基于以下逐块分析结果，生成一份完整的单书分析报告。

## 书籍信息
- 书名：{book_info.get('title', '未知')}
- 总字数：{book_info.get('total_chars', 0)}
- 总章节数：{book_info.get('total_chapters', 0)}
- 分析块数：{len(analysis_results)}

## 分析结果摘要（前10块）
{chr(10).join(summaries)}

## 输出要求

以Markdown格式生成报告，包含以下部分：

# 《{book_info.get('title', '作品')}》分析报告

## 一、整体概况
- 字数规模
- 章节结构
- 题材类型

## 二、作者风格特征

### 2.1 文笔特点
- 句式风格
- 描写手法
- 对话特色

### 2.2 叙事节奏
- 快慢节奏分布
- 信息密度控制
- 转场方式

### 2.3 人物塑造
- 主要人物特点
- 塑造手法
- 人物发展轨迹

## 三、情节设计

### 3.1 冲突设计
- 主要冲突类型
- 冲突升级方式
- 冲突解决模式

### 3.2 爽点设计
- 主要爽点类型
- 铺垫方式
- 密度分布

### 3.3 悬念伏笔
- 悬念设置
- 伏笔埋设
- 回收方式

## 四、可迁移技巧总结

### 4.1 场景技巧（列举5-10个）
### 4.2 文笔技巧（列举5-10个）
### 4.3 大纲技巧（列举5-10个）

## 五、亮点与特色
"""
        return prompt

    def build_cross_book_synthesis_prompt(self, book_reports: list) -> str:
        """构建跨书归纳prompt"""

        books_info = "\n".join([
            f"{i+1}. 《{report.get('title', f'书籍{i+1}')}》"
            for i, report in enumerate(book_reports)
        ])

        prompt = f"""
你是一位资深的小说创作导师。请基于以下{len(book_reports)}本样本的分析报告，归纳出可复用的写作规律。

## 样本书籍
{books_info}

## 输出要求

以Markdown格式生成归纳报告，包含：

# 样本书籍写作规律归纳

## 一、作者风格总结

### 1.1 共同文笔特征
- 列举所有样本共享的文笔特点
- 标注出现频率

### 1.2 独特风格元素
- 区别于其他作品的特色
- 可迁移程度评估

## 二、类型规律总结

### 2.1 题材共性
- 共同的题材元素
- 类型特征

### 2.2 情节模式
- 常见的情节模式
- 冲突设计规律
- 爽点分布规律

### 2.3 人物塑造规律
- 主角设定规律
- 配角设定规律
- 反派设定规律

## 三、可迁移技巧矩阵

为每个技巧标注：
- 技巧名称
- 适用场景
- 使用频率
- 效果评估
- 迁移难度

### 3.1 高频场景技巧（TOP 10）
### 3.2 高频文笔技巧（TOP 10）
### 3.3 高频大纲技巧（TOP 10）

## 四、创作建议

### 4.1 必须保留的特征
### 4.2 可以变化的元素
### 4.3 需要避免的问题

## 五、项目专属Skill生成建议

基于以上分析，建议生成以下项目专属Skills：
1. 项目正文创作Skill
2. 项目大纲执行Skill
3. 项目审查Skill
"""
        return prompt
