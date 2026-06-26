---
title: {{project_name}}审查Skill
description: 基于{{sample_count}}本样本归纳的质量审查和修改指南
version: 1.0.0
type: review
generated_from: {{project_id}}
sample_books: {{sample_titles}}
created_at: {{created_at}}
---

# {{project_name}}审查Skill

## 项目概述

本Skill用于审查创作内容，确保符合项目风格和质量标准。

**样本书籍**：{{sample_list}}

**审查维度**：
- 风格一致性
- 技巧运用
- 质量问题
- 连续性检查

---

## 一、风格一致性检查

### 1.1 文笔风格检查

**核心风格特征**（必须保持）：
{{#style_features}}
- **{{feature}}**：{{description}}
  - 检查方法：{{check_method}}
  - 常见偏离：{{common_deviation}}
  - 修改建议：{{fix_suggestion}}
{{/style_features}}

### 1.2 叙事风格检查

**叙事特点**：
{{#narrative_features}}
- {{feature}}：{{description}}
{{/narrative_features}}

**检查要点**：
{{#narrative_checks}}
- [ ] {{check_point}}
{{/narrative_checks}}

### 1.3 对话风格检查

**项目对话特点**：
{{dialogue_style_description}}

**检查清单**：
{{#dialogue_checks}}
- [ ] {{check_point}}
{{/dialogue_checks}}

---

## 二、技巧运用检查

### 2.1 高频技巧检查

以下技巧在样本中高频出现，应确保在创作中体现：

{{#high_freq_techniques}}
**{{technique}}**（期望频率：{{expected_frequency}}%）

- **检查方法**：{{check_method}}
- **判断标准**：{{criteria}}
- **未达标处理**：{{handling}}

{{/high_freq_techniques}}

### 2.2 场景技巧检查

{{#scene_technique_checks}}
**{{scene_type}}场景**

应包含的技巧：
{{#techniques}}
- {{technique}}：{{description}}
{{/techniques}}

检查要点：
{{#check_points}}
- [ ] {{point}}
{{/check_points}}

{{/scene_technique_checks}}

### 2.3 爽点检查

**爽点密度检查**：
- 期望密度：每{{expected_appeal_interval}}章一个大爽点
- 实际密度：____ 章/个
- 是否达标：是 / 否

**爽点质量检查**：
{{#appeal_quality_checks}}
- [ ] {{check_point}}
{{/appeal_quality_checks}}

---

## 三、质量问题检查

### 3.1 常见问题清单

根据样本和经验，以下是需要重点检查的问题：

#### 3.1.1 情节问题

{{#plot_issues}}
**问题：{{issue}}**
- 表现形式：{{manifestation}}
- 检查方法：{{check_method}}
- 修改方案：{{fix_method}}
- 严重程度：{{severity}}

{{/plot_issues}}

#### 3.1.2 人物问题

{{#character_issues}}
**问题：{{issue}}**
- 表现形式：{{manifestation}}
- 检查方法：{{check_method}}
- 修改方案：{{fix_method}}
- 严重程度：{{severity}}

{{/character_issues}}

#### 3.1.3 文笔问题

{{#writing_issues}}
**问题：{{issue}}**
- 表现形式：{{manifestation}}
- 检查方法：{{check_method}}
- 修改方案：{{fix_method}}
- 严重程度：{{severity}}

{{/writing_issues}}

#### 3.1.4 逻辑问题

{{#logic_issues}}
**问题：{{issue}}**
- 表现形式：{{manifestation}}
- 检查方法：{{check_method}}
- 修改方案：{{fix_method}}
- 严重程度：{{severity}}

{{/logic_issues}}

### 3.2 需要避免的元素

样本分析发现应该避免的元素：

{{#avoid_elements}}
**{{element}}**
- 原因：{{reason}}
- 替代方案：{{alternative}}
- 示例：{{example}}

{{/avoid_elements}}

---

## 四、连续性检查

### 4.1 人物连续性

**检查项目**：
{{#character_continuity_checks}}
- [ ] {{check_item}}
{{/character_continuity_checks}}

**常见连续性错误**：
{{#character_continuity_errors}}
- {{error}}：{{solution}}
{{/character_continuity_errors}}

### 4.2 设定连续性

**检查项目**：
{{#setting_continuity_checks}}
- [ ] {{check_item}}
{{/setting_continuity_checks}}

**常见连续性错误**：
{{#setting_continuity_errors}}
- {{error}}：{{solution}}
{{/setting_continuity_errors}}

### 4.3 剧情连续性

**检查项目**：
{{#plot_continuity_checks}}
- [ ] {{check_item}}
{{/plot_continuity_checks}}

**常见连续性错误**：
{{#plot_continuity_errors}}
- {{error}}：{{solution}}
{{/plot_continuity_errors}}

### 4.4 时间线检查

**检查要点**：
{{#timeline_checks}}
- [ ] {{check_point}}
{{/timeline_checks}}

---

## 五、结构检查

### 5.1 章节结构检查

**标准结构**：
{{standard_chapter_structure}}

**检查清单**：
{{#chapter_structure_checks}}
- [ ] {{check_item}}
{{/chapter_structure_checks}}

**结构问题处理**：
{{#structure_issues}}
- **{{issue}}**：{{solution}}
{{/structure_issues}}

### 5.2 节奏检查

**期望节奏**（根据大纲）：____

**实际节奏评估**：
- [ ] 开头节奏（1-20%）：快/中/慢，是否符合规划
- [ ] 中段节奏（20-80%）：快/中/慢，是否符合规划
- [ ] 结尾节奏（80-100%）：快/中/慢，是否符合规划

**节奏问题处理**：
{{#rhythm_issues}}
- **{{issue}}**：{{solution}}
{{/rhythm_issues}}

### 5.3 信息密度检查

**期望密度**（根据大纲）：____

**实际密度评估**：
- 新信息数量：____ 项
- 密度级别：高/中/低
- 是否符合规划：是 / 否

**密度问题处理**：
{{#density_issues}}
- **{{issue}}**：{{solution}}
{{/density_issues}}

---

## 六、分级审查流程

### 6.1 快速审查（5-10分钟/章）

适用于初稿完成后的快速过滤。

**检查项目**：
{{#quick_review_items}}
- [ ] {{item}}
{{/quick_review_items}}

**判断标准**：
- 通过：基本符合要求，可进入详细审查
- 不通过：存在严重问题，需要重写

### 6.2 详细审查（20-30分钟/章）

适用于通过快速审查的章节。

**检查项目**：
{{#detailed_review_items}}
- [ ] {{item}}
{{/detailed_review_items}}

**评分标准**：
- 风格一致性：____ / 10分
- 技巧运用：____ / 10分
- 质量水平：____ / 10分
- 连续性：____ / 10分
- 结构完整性：____ / 10分
- **总分**：____ / 50分

**通过标准**：总分≥40分

### 6.3 精修审查（30-60分钟/章）

适用于重要章节（如高潮章节）。

**检查项目**：
{{#refined_review_items}}
- [ ] {{item}}
{{/refined_review_items}}

**额外关注**：
{{#refined_focus_areas}}
- {{area}}
{{/refined_focus_areas}}

---

## 七、修改建议模板

### 7.1 轻微问题修改

**问题描述**：
> [具体描述问题]

**位置**：第X段/第X句

**严重程度**：轻微

**修改建议**：
> [具体修改建议]

**修改后效果**：
> [预期效果]

### 7.2 中等问题修改

**问题描述**：
> [具体描述问题]

**位置**：第X-Y段

**严重程度**：中等

**问题分析**：
> [分析问题原因]

**修改建议**：
> [具体修改建议，可能包含多个选项]

**修改示例**：
```
[展示修改前后对比]
```

### 7.3 严重问题修改

**问题描述**：
> [具体描述问题]

**影响范围**：第X章整体 / 涉及第X-Y章

**严重程度**：严重

**问题分析**：
> [深入分析问题原因和影响]

**修改建议**：
> [详细的修改方案，可能需要重写]

**相关章节调整**：
> [如果影响其他章节，列出需要调整的地方]

---

## 八、自查清单

### 8.1 创作完成后立即自查

完成章节后，立即使用此清单进行自查：

#### 基本要素（必须全部满足）
{{#basic_checks}}
- [ ] {{check}}
{{/basic_checks}}

#### 质量要素（至少满足80%）
{{#quality_checks}}
- [ ] {{check}}
{{/quality_checks}}

#### 风格要素（至少满足90%）
{{#style_checks}}
- [ ] {{check}}
{{/style_checks}}

### 8.2 隔天复查

完成24小时后，重新审查：

{{#delayed_checks}}
- [ ] {{check}}
{{/delayed_checks}}

### 8.3 卷完成后整体审查

完成一卷后，进行整体审查：

{{#volume_checks}}
- [ ] {{check}}
{{/volume_checks}}

---

## 九、常见修改策略

### 9.1 增强风格一致性

**策略1：文笔调整**
```
问题：文笔过于{特点}
方法：
1. 识别与项目风格不符的句子
2. 参考样本风格进行改写
3. 保持核心内容不变
```

**策略2：对话调整**
```
问题：对话不符合人物性格
方法：
1. 回顾人物设定
2. 参考该人物之前的对话
3. 调整语气、用词、句式
```

### 9.2 强化技巧运用

**策略1：补充缺失技巧**
```
问题：缺少项目核心技巧
方法：
1. 识别可以插入技巧的位置
2. 自然融入，避免生硬
3. 保持整体流畅
```

**策略2：优化现有技巧**
```
问题：技巧运用不够充分
方法：
1. 找到技巧使用的地方
2. 增强细节描写
3. 提升表现力度
```

### 9.3 提升爽点效果

**策略1：强化铺垫**
```
问题：爽点爆发不够有力
方法：
1. 检查前置铺垫是否充分
2. 补充压抑/憋屈的描写
3. 增强期待感
```

**策略2：优化爆发**
```
问题：爆发时刻表现平淡
方法：
1. 强化感官描写
2. 增加情绪渲染
3. 突出转折对比
```

### 9.4 修复逻辑问题

**策略1：补充逻辑链条**
```
问题：逻辑跳跃
方法：
1. 找出跳跃的环节
2. 补充中间步骤
3. 确保因果清晰
```

**策略2：消除矛盾**
```
问题：前后矛盾
方法：
1. 确认正确的设定
2. 修改矛盾的部分
3. 检查相关章节
```

---

## 十、质量标准

### 10.1 及格标准（可以发布）

{{#pass_criteria}}
- {{criterion}}
{{/pass_criteria}}

### 10.2 良好标准（值得推荐）

{{#good_criteria}}
- {{criterion}}
{{/good_criteria}}

### 10.3 优秀标准（精品级别）

{{#excellent_criteria}}
- {{criterion}}
{{/excellent_criteria}}

---

## 十一、审查记录模板

### 11.1 章节审查记录

```markdown
## 第X章审查记录

**审查日期**：YYYY-MM-DD
**审查者**：[姓名/AI]
**审查类型**：快速/详细/精修

### 评分
- 风格一致性：__/10
- 技巧运用：__/10
- 质量水平：__/10
- 连续性：__/10
- 结构完整性：__/10
- **总分**：__/50

### 发现的问题
| 位置 | 问题 | 严重程度 | 状态 |
|------|------|----------|------|
| 第X段 | [问题描述] | 轻微/中等/严重 | 待修改/已修改 |

### 修改建议
[详细的修改建议]

### 优点记录
[值得保持的优点]

### 审查结论
- [ ] 通过，无需修改
- [ ] 通过，建议微调
- [ ] 需要修改后重审
- [ ] 需要重写
```

---

## 十二、使用指南

### 12.1 审查时机

**推荐审查流程**：
1. 创作完成后立即自查（快速审查）
2. 隔天进行详细审查
3. 重要章节进行精修审查
4. 卷完成后进行整体审查

### 12.2 审查重点

根据章节类型调整审查重点：

{{#review_focus}}
**{{chapter_type}}**：
- 重点关注：{{focus_areas}}
- 常见问题：{{common_issues}}

{{/review_focus}}

### 12.3 与其他Skill配合

- **正文创作Skill**：提供风格标准参考
- **大纲执行Skill**：提供结构标准参考

### 12.4 效率建议

- 使用检查清单，避免遗漏
- 先整体后局部
- 先严重问题后轻微问题
- 批量处理同类问题
- 保留审查记录，积累经验

---

## 附录：问题代码速查表

| 代码 | 问题类型 | 严重程度 |
|------|----------|----------|
{{#issue_codes}}
| {{code}} | {{type}} | {{severity}} |
{{/issue_codes}}

---

**本Skill基于样本分析和行业经验生成，建议根据实际情况灵活运用。**
