---
title: {{project_name}}大纲执行Skill
description: 基于{{sample_count}}本样本归纳的大纲执行和节奏控制指南
version: 1.0.0
type: outline
generated_from: {{project_id}}
sample_books: {{sample_titles}}
created_at: {{created_at}}
---

# {{project_name}}大纲执行Skill

## 项目概述

本Skill专注于指导如何执行大纲，控制节奏，确保故事结构符合样本规律。

**样本书籍**：{{sample_list}}

**核心关注**：
- 节奏控制
- 信息密度
- 转场方式
- 结构规律

---

## 一、整体结构规律

### 1.1 分卷结构

根据样本分析，本项目的典型结构为：

**总字数范围**：{{word_count_range}}

**分卷规律**：
- 卷数：{{volume_count_range}}
- 每卷字数：{{words_per_volume}}
- 每卷章节数：{{chapters_per_volume}}

**分卷节奏**：
```
{{volume_pattern}}
```

### 1.2 章节结构

**章节长度**：
- 标准长度：{{standard_chapter_length}}字
- 范围：{{chapter_length_range}}字
- 特殊章节（如高潮）：{{special_chapter_length}}字

**章节类型分布**：
{{#chapter_types}}
- {{type}}：{{percentage}}%（{{description}}）
{{/chapter_types}}

---

## 二、节奏控制技巧

### 2.1 整体节奏模式

样本分析显示的节奏模式：

{{rhythm_pattern_description}}

**节奏曲线**：
```
{{rhythm_curve}}
```

### 2.2 快节奏场景（占比{{fast_pace_ratio}}%）

**特征**：
{{#fast_pace_features}}
- {{feature}}
{{/fast_pace_features}}

**适用场景**：
{{#fast_pace_scenarios}}
- {{scenario}}
{{/fast_pace_scenarios}}

**控制技巧**：
{{#fast_pace_techniques}}
**{{technique}}**
- 实现方式：{{method}}
- 持续时长：{{duration}}
- 注意事项：{{notes}}

{{/fast_pace_techniques}}

### 2.3 慢节奏场景（占比{{slow_pace_ratio}}%）

**特征**：
{{#slow_pace_features}}
- {{feature}}
{{/slow_pace_features}}

**适用场景**：
{{#slow_pace_scenarios}}
- {{scenario}}
{{/slow_pace_scenarios}}

**控制技巧**：
{{#slow_pace_techniques}}
**{{technique}}**
- 实现方式：{{method}}
- 持续时长：{{duration}}
- 注意事项：{{notes}}

{{/slow_pace_techniques}}

### 2.4 节奏转换

**从快到慢**：
{{#fast_to_slow}}
- {{method}}
{{/fast_to_slow}}

**从慢到快**：
{{#slow_to_fast}}
- {{method}}
{{/slow_to_fast}}

---

## 三、信息密度控制

### 3.1 信息密度层次

根据样本分析，信息密度分为三个层次：

**高密度**（{{high_density_ratio}}%的章节）
- 定义：{{high_density_definition}}
- 使用时机：{{high_density_timing}}
- 包含要素：{{high_density_elements}}

**中密度**（{{medium_density_ratio}}%的章节）
- 定义：{{medium_density_definition}}
- 使用时机：{{medium_density_timing}}
- 包含要素：{{medium_density_elements}}

**低密度**（{{low_density_ratio}}%的章节）
- 定义：{{low_density_definition}}
- 使用时机：{{low_density_timing}}
- 包含要素：{{low_density_elements}}

### 3.2 信息密度分布规律

{{#density_distribution}}
**{{stage}}阶段**：
- 主要密度：{{main_density}}
- 占比：{{ratio}}
- 目的：{{purpose}}

{{/density_distribution}}

### 3.3 信息投放技巧

{{#info_delivery_techniques}}
**{{technique}}**（频率：{{frequency}}%）
- 适用场景：{{scenario}}
- 投放时机：{{timing}}
- 投放量：{{amount}}
- 效果：{{effect}}

{{/info_delivery_techniques}}

---

## 四、转场技巧

### 4.1 转场类型统计

{{#transition_types}}
- **{{type}}**：{{count}}次（{{percentage}}%）
{{/transition_types}}

### 4.2 章节间转场

{{#chapter_transitions}}
**{{transition_type}}**（使用率：{{frequency}}%）

**使用场景**：{{scenario}}

**实现方式**：
{{#methods}}
- {{method}}
{{/methods}}

**示例**：
```
{{example}}
```

**注意事项**：{{notes}}

{{/chapter_transitions}}

### 4.3 场景内转场

{{#scene_transitions}}
**{{transition_type}}**（使用率：{{frequency}}%）

**使用场景**：{{scenario}}

**实现方式**：
{{#methods}}
- {{method}}
{{/methods}}

**示例**：
```
{{example}}
```

{{/scene_transitions}}

---

## 五、关键节点设置

### 5.1 卷级关键节点

每卷必须包含的关键节点：

{{#volume_milestones}}
**{{milestone}}**（位置：卷的{{position}}）
- 作用：{{function}}
- 必需元素：{{required_elements}}
- 强度要求：{{intensity}}

{{/volume_milestones}}

### 5.2 章级关键节点

高频出现的章级节点：

{{#chapter_milestones}}
**{{milestone}}**（出现频率：{{frequency}}%）
- 典型位置：{{position}}
- 作用：{{function}}
- 处理方式：{{handling}}

{{/chapter_milestones}}

---

## 六、悬念布局

### 6.1 悬念层级

**长线悬念**（跨卷级）
- 数量：{{long_suspense_count}}
- 设置时机：{{long_suspense_timing}}
- 维持手法：{{long_suspense_maintain}}
- 解决时机：{{long_suspense_resolution}}

**中线悬念**（卷内级）
- 数量：{{medium_suspense_count}}
- 设置时机：{{medium_suspense_timing}}
- 维持手法：{{medium_suspense_maintain}}
- 解决时机：{{medium_suspense_resolution}}

**短线悬念**（章节级）
- 数量：{{short_suspense_count}}
- 设置时机：{{short_suspense_timing}}
- 维持手法：{{short_suspense_maintain}}
- 解决时机：{{short_suspense_resolution}}

### 6.2 悬念密度

{{#suspense_density}}
**{{stage}}**：
- 长线悬念：{{long}}个
- 中线悬念：{{medium}}个
- 短线悬念：{{short}}个/章
- 总压力值：{{pressure}}

{{/suspense_density}}

---

## 七、爽点分布

### 7.1 爽点密度规律

根据样本统计：

**整体密度**：平均每{{爽点_interval}}章一个大爽点

**分布规律**：
{{#appeal_distribution}}
- {{stage}}：{{density}}（{{reason}}）
{{/appeal_distribution}}

### 7.2 爽点强度曲线

```
{{appeal_intensity_curve}}
```

**控制原则**：
{{#appeal_principles}}
- {{principle}}
{{/appeal_principles}}

### 7.3 爽点类型轮换

**推荐轮换模式**：
{{#appeal_rotation}}
{{index}}. {{appeal_type}}（第{{chapter}}章） → 
{{/appeal_rotation}}

---

## 八、大纲执行清单

### 8.1 开始新卷前检查

- [ ] 上一卷的主要冲突是否已解决
- [ ] 本卷的核心冲突是否明确
- [ ] 本卷的目标状态是否清晰
- [ ] 长线悬念是否有推进计划
- [ ] 新角色/新要素是否准备就绪

### 8.2 开始新章前检查

- [ ] 本章在卷中的位置和作用是否明确
- [ ] 本章的节奏类型是否符合整体规划
- [ ] 本章的信息密度是否恰当
- [ ] 本章是否有明确的推进目标
- [ ] 与上一章的转场是否自然

### 8.3 完成章节后检查

- [ ] 本章是否达成了预定目标
- [ ] 节奏控制是否符合规划
- [ ] 信息投放是否适量
- [ ] 悬念/伏笔是否有效设置
- [ ] 爽点设计是否到位
- [ ] 与下一章的衔接是否流畅

### 8.4 完成卷后检查

- [ ] 本卷目标是否全部达成
- [ ] 主要冲突是否有效解决
- [ ] 人物状态是否有明显变化
- [ ] 长线悬念是否有推进
- [ ] 为下一卷是否留下足够钩子

---

## 九、常见问题处理

### 9.1 节奏失控

**症状**：
{{#rhythm_problems}}
- {{symptom}}
{{/rhythm_problems}}

**解决方案**：
{{#rhythm_solutions}}
- {{solution}}
{{/rhythm_solutions}}

### 9.2 信息过载

**症状**：
{{#info_overload_problems}}
- {{symptom}}
{{/info_overload_problems}}

**解决方案**：
{{#info_overload_solutions}}
- {{solution}}
{{/info_overload_solutions}}

### 9.3 悬念不足

**症状**：
{{#suspense_problems}}
- {{symptom}}
{{/suspense_problems}}

**解决方案**：
{{#suspense_solutions}}
- {{solution}}
{{/suspense_solutions}}

---

## 十、分卷规划模板

### 10.1 卷规划模板

```markdown
## 第X卷：【卷名】

**字数目标**：{{target_words}}字
**章节数**：{{target_chapters}}章

### 核心目标
- 主线目标：
- 副线目标：
- 人物成长：

### 主要冲突
- 外部冲突：
- 内部冲突：
- 解决方式：

### 关键节点
1. 开卷节点（第X章）：
2. 1/4节点（第X章）：
3. 中点节点（第X章）：
4. 3/4节点（第X章）：
5. 卷末高潮（第X章）：

### 悬念布局
- 本卷新增悬念：
- 解决的悬念：
- 推进的长线悬念：

### 爽点计划
- 大爽点（3-5个）：
- 小爽点分布：

### 新增要素
- 新角色：
- 新地点：
- 新设定：
```

### 10.2 章节规划模板

```markdown
## 第X章：【章节标题】

**字数目标**：{{target_words}}字
**节奏类型**：快/中/慢
**信息密度**：高/中/低

### 本章目标
- 剧情推进：
- 人物发展：
- 信息揭示：

### 场景设计
1. 场景A（字数，节奏）：
2. 场景B（字数，节奏）：
3. ...

### 关键要素
- 冲突点：
- 爽点设计：
- 悬念/伏笔：
- 转场方式：

### 承接关系
- 承接上章：
- 引出下章：
```

---

## 十一、使用指南

### 11.1 大纲阶段使用

1. **整体规划**：
   - 确定总字数和分卷数
   - 根据节奏模式规划每卷的定位
   - 设计长线悬念和伏笔

2. **分卷规划**：
   - 使用"分卷规划模板"
   - 明确每卷的核心目标和冲突
   - 规划关键节点位置

3. **章节规划**：
   - 使用"章节规划模板"
   - 注意节奏类型的轮换
   - 控制信息密度分布

### 11.2 创作阶段使用

1. **执行前**：
   - 查看本章在整体中的位置
   - 确认节奏和密度要求
   - 明确本章的关键要素

2. **执行中**：
   - 按照规划的场景展开
   - 注意节奏和密度控制
   - 确保关键要素不遗漏

3. **执行后**：
   - 使用清单检查完成度
   - 评估是否达成目标
   - 调整后续章节规划（如需要）

### 11.3 与其他Skill配合

- **正文创作Skill**：提供具体的写作技巧
- **审查Skill**：提供结构性检查

---

## 十二、进度追踪

### 12.1 推荐追踪指标

- 当前进度：X/XX卷，X/XX章
- 已完成字数：XX/XX万字
- 当前节奏状态：快/中/慢
- 悬念数量：长线X个，中线X个，短线X个
- 最近爽点间隔：X章

### 12.2 异常预警

以下情况需要注意调整：

- 连续{{max_slow_chapters}}章慢节奏
- 连续{{max_fast_chapters}}章快节奏
- 爽点间隔超过{{max_appeal_gap}}章
- 信息密度连续{{max_high_density_chapters}}章偏高

---

**本Skill基于样本分析生成，建议在执行过程中根据实际情况灵活调整。**
