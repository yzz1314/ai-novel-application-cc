"""
Phase 3 代码结构验证
验证Skill生成相关文件是否正确创建
"""
from pathlib import Path


def check_file_exists(file_path: str, description: str) -> bool:
    """检查文件是否存在"""
    path = Path(file_path)
    exists = path.exists()
    status = "✓" if exists else "✗"
    print(f"  {status} {description:50s} {'存在' if exists else '不存在'}")
    return exists


def check_phase3_structure():
    """检查Phase 3目录结构"""
    print("=" * 70)
    print("Phase 3 代码结构验证")
    print("=" * 70)

    base_path = Path(__file__).parent
    results = []

    # Skill模板
    print("\n1. Skill模板:")
    results.append(check_file_exists(
        base_path / "skills/templates/writing_skill_template.md",
        "正文创作Skill模板"
    ))
    results.append(check_file_exists(
        base_path / "skills/templates/outline_skill_template.md",
        "大纲执行Skill模板"
    ))
    results.append(check_file_exists(
        base_path / "skills/templates/review_skill_template.md",
        "审查Skill模板"
    ))

    # SkillGeneratorAgent
    print("\n2. SkillGeneratorAgent:")
    results.append(check_file_exists(
        base_path / "agents/skill_generator_agent.py",
        "SkillGeneratorAgent实现"
    ))

    # 更新的文件
    print("\n3. 更新的文件:")
    results.append(check_file_exists(
        base_path / "agents/__init__.py",
        "Agents包导出更新"
    ))
    results.append(check_file_exists(
        base_path / "api/routes/agent_routes.py",
        "Agent路由注册"
    ))

    return results


def check_file_content():
    """检查文件内容关键部分"""
    print("\n" + "=" * 70)
    print("检查文件内容")
    print("=" * 70)

    base_path = Path(__file__).parent
    checks = []

    # 检查agents/__init__.py是否包含SkillGeneratorAgent
    print("\n1. 检查agents/__init__.py:")
    agents_init = base_path / "agents/__init__.py"
    if agents_init.exists():
        content = agents_init.read_text()
        exists = "SkillGeneratorAgent" in content
        status = "✓" if exists else "✗"
        print(f"  {status} SkillGeneratorAgent已导出")
        checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.append(False)

    # 检查agent_routes.py是否注册了skill_generation
    print("\n2. 检查agent_routes.py:")
    routes_file = base_path / "api/routes/agent_routes.py"
    if routes_file.exists():
        content = routes_file.read_text()
        exists = "skill_generation" in content
        status = "✓" if exists else "✗"
        print(f"  {status} skill_generation已注册")
        checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.append(False)

    # 检查模板文件格式
    print("\n3. 检查模板文件格式:")
    templates = [
        ("writing_skill_template.md", "正文创作"),
        ("outline_skill_template.md", "大纲执行"),
        ("review_skill_template.md", "审查")
    ]

    for template_file, name in templates:
        template_path = base_path / "skills/templates" / template_file
        if template_path.exists():
            content = template_path.read_text()
            has_frontmatter = "---" in content[:50]
            has_variables = "{{" in content and "}}" in content
            valid = has_frontmatter and has_variables
            status = "✓" if valid else "✗"
            print(f"  {status} {name}模板格式正确")
            checks.append(valid)
        else:
            print(f"  ✗ {name}模板文件不存在")
            checks.append(False)

    return checks


def count_lines():
    """统计代码行数"""
    print("\n" + "=" * 70)
    print("Phase 3 代码行数统计")
    print("=" * 70)

    base_path = Path(__file__).parent
    files = [
        ("skills/templates/writing_skill_template.md", "正文创作Skill模板"),
        ("skills/templates/outline_skill_template.md", "大纲执行Skill模板"),
        ("skills/templates/review_skill_template.md", "审查Skill模板"),
        ("agents/skill_generator_agent.py", "SkillGeneratorAgent"),
    ]

    total_lines = 0
    for file_path, description in files:
        full_path = base_path / file_path
        if full_path.exists():
            lines = len(full_path.read_text().splitlines())
            total_lines += lines
            print(f"  {description:30s} : {lines:4d} 行")
        else:
            print(f"  {description:30s} : 文件不存在")

    print(f"\n  {'Phase 3 新增代码总计':30s} : {total_lines:4d} 行")
    return total_lines


def check_templates_completeness():
    """检查模板完整性"""
    print("\n" + "=" * 70)
    print("模板完整性检查")
    print("=" * 70)

    base_path = Path(__file__).parent

    template_checks = {
        "writing_skill_template.md": [
            "文笔特点",
            "场景技巧",
            "冲突设计",
            "爽点设计",
            "人物塑造",
            "悬念与伏笔",
            "创作要点",
            "章节创作清单"
        ],
        "outline_skill_template.md": [
            "整体结构规律",
            "节奏控制技巧",
            "信息密度控制",
            "转场技巧",
            "关键节点设置",
            "悬念布局",
            "爽点分布"
        ],
        "review_skill_template.md": [
            "风格一致性检查",
            "技巧运用检查",
            "质量问题检查",
            "连续性检查",
            "结构检查",
            "分级审查流程"
        ]
    }

    all_complete = True

    for template_file, required_sections in template_checks.items():
        print(f"\n{template_file}:")
        template_path = base_path / "skills/templates" / template_file

        if not template_path.exists():
            print("  ✗ 文件不存在")
            all_complete = False
            continue

        content = template_path.read_text()

        for section in required_sections:
            exists = section in content
            status = "✓" if exists else "✗"
            print(f"  {status} {section}")
            if not exists:
                all_complete = False

    return all_complete


def main():
    """主函数"""
    print("\n" + "=" * 70)
    print("Phase 3 完整性验证")
    print("=" * 70)

    # 检查文件结构
    structure_results = check_phase3_structure()

    # 检查文件内容
    content_results = check_file_content()

    # 检查模板完整性
    templates_complete = check_templates_completeness()

    # 统计代码行数
    total_lines = count_lines()

    # 汇总
    print("\n" + "=" * 70)
    print("验证结果汇总")
    print("=" * 70)

    structure_passed = sum(structure_results)
    structure_total = len(structure_results)
    print(f"文件结构检查: {structure_passed}/{structure_total} 通过")

    content_passed = sum(content_results)
    content_total = len(content_results)
    print(f"内容检查:     {content_passed}/{content_total} 通过")

    templates_status = "✓ 通过" if templates_complete else "✗ 未通过"
    print(f"模板完整性:   {templates_status}")

    print(f"新增代码量:   {total_lines} 行")

    all_passed = (
        structure_passed == structure_total and
        content_passed == content_total and
        templates_complete
    )

    print("\n" + "=" * 70)
    if all_passed:
        print("✓ 所有检查通过！Phase 3 代码结构完整")
    else:
        print("✗ 部分检查未通过，请检查上述错误")
    print("=" * 70)

    return all_passed


if __name__ == "__main__":
    import sys
    success = main()
    sys.exit(0 if success else 1)
