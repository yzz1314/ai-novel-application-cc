---
title: {{project_name}}正文创作Skill
description: 基于{{sample_count}}本样本归纳的专属正文创作指南
version: 1.0.0
type: writing
generated_from: {{project_id}}
sample_books: {{sample_titles}}
created_at: {{created_at}}
---

# {{project_name}}正文创作Skill

## 项目概述

本Skill基于对{{sample_count}}本样本小说的深度分析自动生成，涵盖了该类型小说的核心写作技巧和风格特征。

**样本书籍**：
{{sample_list}}

**分析数据**：
- 总分析块数：{{total_chunks}}
- 提取技巧总数：{{total_techniques}}
- 生成时间：{{created_at}}

---

## 一、核心风格特征

### 1.1 文笔特点

基于分析，本项目的文笔呈现以下特征（按出现频率排序）：

{{#prose_techniques}}
**{{index}}. {{technique_name}}**（出现率：{{frequency}}%）
- **描述**：{{description}}
- **使用场景**：{{usage_scenario}}
- **示例**：
  ```
  {{example}}
  ```
- **使用建议**：{{suggestion}}

{{/prose_techniques}}

### 1.2 叙事节奏

{{narrative_pace_description}}

**节奏控制要点**：
{{#pace_points}}
- {{point}}
{{/pace_points}}

---

## 二、场景技巧

### 2.1 战斗场景

{{#scene_battle}}
**技巧：{{technique}}**（出现率：{{frequency}}%）
- **使用时机**：{{timing}}
- **核心要素**：{{core_elements}}
- **示例片段**：
  ```
  {{example}}
  ```
- **注意事项**：{{notes}}

{{/scene_battle}}

### 2.2 日常场景

{{#scene_daily}}
**技巧：{{technique}}**（出现率：{{frequency}}%）
- **使用时机**：{{timing}}
- **核心要素**：{{core_elements}}
- **示例片段**：
  ```
  {{example}}
  ```
- **注意事项**：{{notes}}

{{/scene_daily}}

### 2.3 情感场景

{{#scene_emotion}}
**技巧：{{technique}}**（出现率：{{frequency}}%）
- **使用时机**：{{timing}}
- **核心要素**：{{core_elements}}
- **示例片段**：
  ```
  {{example}}
  ```
- **注意事项**：{{notes}}

{{/scene_emotion}}

---

## 三、冲突设计

### 3.1 冲突类型分布

根据样本分析：
- 外部冲突：{{external_conflict_ratio}}%
- 内部冲突：{{internal_conflict_ratio}}%
- 社会冲突：{{social_conflict_ratio}}%

### 3.2 冲突设计技巧

{{#conflict_techniques}}
**{{technique_name}}**（出现率：{{frequency}}%）
- **适用场景**：{{scenario}}
- **设计要点**：{{design_points}}
- **升级方式**：{{escalation}}
- **解决方式**：{{resolution}}

{{/conflict_techniques}}

---

## 四、爽点设计

### 4.1 爽点类型统计

{{#appeal_types}}
- **{{type}}**：出现{{count}}次（{{percentage}}%）
{{/appeal_types}}

### 4.2 高频爽点设计

{{#appeal_techniques}}
**{{index}}. {{appeal_type}}**（出现率：{{frequency}}%）

**铺垫方式**：
{{#foreshadowing}}
- {{item}}
{{/foreshadowing}}

**爆发时机**：{{burst_timing}}

**强度控制**：{{intensity_control}}

**示例场景**：
```
{{example}}
```

**使用建议**：{{suggestion}}

{{/appeal_techniques}}

---

## 五、人物塑造

### 5.1 主角塑造规律

{{protagonist_pattern}}

**关键要素**：
{{#protagonist_elements}}
- {{element}}
{{/protagonist_elements}}

### 5.2 配角塑造规律

{{supporting_pattern}}

**关键要素**：
{{#supporting_elements}}
- {{element}}
{{/supporting_elements}}

### 5.3 人物对话特点

{{dialogue_style}}

**对话技巧**：
{{#dialogue_techniques}}
- **{{technique}}**：{{description}}
{{/dialogue_techniques}}

---

## 六、悬念与伏笔

### 6.1 悬念设置技巧

{{#suspense_techniques}}
**{{technique}}**（出现率：{{frequency}}%）
- **设置时机**：{{timing}}
- **保持手法**：{{maintain_method}}
- **揭示方式**：{{reveal_method}}

{{/suspense_techniques}}

### 6.2 伏笔埋设技巧

{{#foreshadowing_techniques}}
**{{technique}}**（出现率：{{frequency}}%）
- **埋设方式**：{{planting_method}}
- **涉及要素**：{{elements}}
- **回收时机**：{{payoff_timing}}

{{/foreshadowing_techniques}}

---

## 七、创作要点

### 7.1 必须遵守的特征

以下特征在所有样本中高频出现，必须在创作中保持：

{{#must_keep}}
- **{{feature}}**：{{reason}}
{{/must_keep}}

### 7.2 可以灵活变化的元素

以下元素可以根据具体情节灵活调整：

{{#flexible_elements}}
- **{{element}}**：{{guideline}}
{{/flexible_elements}}

### 7.3 需要避免的问题

样本分析中发现的常见问题（需要避免）：

{{#avoid_issues}}
- **{{issue}}**：{{solution}}
{{/avoid_issues}}

---

## 八、章节创作清单

每次创作新章节时，请检查以下要点：

### 8.1 开头检查
- [ ] 是否承接上一章节的状态和情绪
- [ ] 是否有清晰的场景设定
- [ ] 是否有吸引读者的开篇钩子

### 8.2 情节推进检查
- [ ] 本章是否推动了主线剧情
- [ ] 是否有至少一个冲突点
- [ ] 是否有适当的爽点设计
- [ ] 节奏是否符合整体规划

### 8.3 人物检查
- [ ] 人物行为是否符合设定
- [ ] 人物状态是否有变化或发展
- [ ] 对话是否符合人物性格

### 8.4 技巧运用检查
- [ ] 是否运用了项目核心文笔技巧
- [ ] 场景描写是否符合项目风格
- [ ] 是否注意了悬念和伏笔

### 8.5 收尾检查
- [ ] 本章是否有明确的结束点
- [ ] 是否为下一章留下钩子
- [ ] 字数是否在目标范围（{{target_word_count}}字）

---

## 九、常见场景模板

### 9.1 战斗场景模板

```
1. 战前准备/状态描述（100-200字）
2. 战斗爆发（50-100字）
3. 战斗过程（核心，800-1500字）
   - 开始压制/被压制
   - 转折点1
   - 转折点2（可选）
   - 最终爆发
4. 战斗结果（100-200字）
5. 战后状态/影响（100-200字）
```

### 9.2 日常场景模板

```
1. 场景设定（50-100字）
2. 人物互动（500-1000字）
   - 对话推进
   - 信息揭示
   - 情感变化
3. 场景转换（50-100字）
```

### 9.3 情感场景模板

```
1. 情绪铺垫（100-200字）
2. 情感触发事件（200-300字）
3. 情感爆发/深化（500-800字）
   - 内心活动
   - 外部表现
   - 他人反应
4. 情感落地/转化（200-300字）
```

---

## 十、使用说明

### 10.1 如何使用本Skill

1. **创作前准备**：
   - 通读本Skill，熟悉项目风格
   - 明确本章节的大纲要求
   - 查看记忆系统中的前文状态

2. **创作中参考**：
   - 根据场景类型选择对应的技巧
   - 参考示例和模板进行创作
   - 注意高频技巧的运用

3. **创作后检查**：
   - 使用"章节创作清单"逐项检查
   - 对比样本风格，确保一致性
   - 必要时进行修改调整

### 10.2 与其他Skill的配合

- **大纲执行Skill**：提供整体节奏和结构指导
- **审查Skill**：提供检查清单和修改建议

### 10.3 Skill更新

本Skill会根据创作实践不断优化：
- 版本：{{version}}
- 生成时间：{{created_at}}
- 下次更新：建议在完成50章后重新生成

---

## 附录：技巧索引

### A.1 文笔技巧索引
{{#prose_index}}
- {{technique}}（P{{page}}）
{{/prose_index}}

### A.2 场景技巧索引
{{#scene_index}}
- {{technique}}（P{{page}}）
{{/scene_index}}

### A.3 爽点技巧索引
{{#appeal_index}}
- {{technique}}（P{{page}}）
{{/appeal_index}}

---

**本Skill由AI自动生成，基于真实样本分析，建议结合人工经验使用。**
