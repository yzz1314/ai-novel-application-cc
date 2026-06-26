"""
Phase 2 代码结构验证
验证所有文件是否正确创建
"""
from pathlib import Path


def check_file_exists(file_path: str, description: str) -> bool:
    """检查文件是否存在"""
    path = Path(file_path)
    exists = path.exists()
    status = "✓" if exists else "✗"
    print(f"  {status} {description:50s} {'存在' if exists else '不存在'}")
    return exists


def check_directory_structure():
    """检查目录结构"""
    print("=" * 70)
    print("检查Python服务目录结构")
    print("=" * 70)

    base_path = Path(__file__).parent
    results = []

    # LLM模块
    print("\n1. LLM模块:")
    results.append(check_file_exists(base_path / "llm/client.py", "LLM客户端"))
    results.append(check_file_exists(base_path / "llm/prompt_builder.py", "Prompt构建器"))
    results.append(check_file_exists(base_path / "llm/__init__.py", "LLM包初始化"))

    # Agents模块
    print("\n2. Agents模块:")
    results.append(check_file_exists(base_path / "agents/base.py", "Agent基类"))
    results.append(check_file_exists(base_path / "agents/sample_import_agent.py", "样本导入Agent"))
    results.append(check_file_exists(base_path / "agents/full_text_analysis_agent.py", "全文分析Agent"))
    results.append(check_file_exists(base_path / "agents/book_summary_agent.py", "单书汇总Agent"))
    results.append(check_file_exists(base_path / "agents/cross_book_synthesis_agent.py", "跨书归纳Agent"))
    results.append(check_file_exists(base_path / "agents/__init__.py", "Agents包初始化"))

    # Skills模块
    print("\n3. Skills模块:")
    results.append(check_file_exists(base_path / "skills/skill_loader.py", "Skill加载器"))
    results.append(check_file_exists(base_path / "skills/__init__.py", "Skills包初始化"))

    # API路由
    print("\n4. API路由:")
    results.append(check_file_exists(base_path / "api/routes/agent_routes.py", "Agent路由"))

    # 配置
    print("\n5. 配置文件:")
    results.append(check_file_exists(base_path / "config.py", "配置文件"))
    results.append(check_file_exists(base_path / "requirements.txt", "依赖文件"))

    return results


def check_file_content():
    """检查文件内容关键部分"""
    print("\n" + "=" * 70)
    print("检查文件内容关键部分")
    print("=" * 70)

    base_path = Path(__file__).parent
    checks = []

    # 检查agents/__init__.py
    print("\n1. 检查agents/__init__.py导出:")
    agents_init = base_path / "agents/__init__.py"
    if agents_init.exists():
        content = agents_init.read_text()
        expected_exports = [
            "BaseAgent",
            "SampleImportAgent",
            "FullTextAnalysisAgent",
            "BookSummaryAgent",
            "CrossBookSynthesisAgent"
        ]
        for export in expected_exports:
            exists = export in content
            status = "✓" if exists else "✗"
            print(f"  {status} {export}")
            checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.append(False)

    # 检查agent_routes.py的AGENT_REGISTRY
    print("\n2. 检查agent_routes.py的Agent注册:")
    routes_file = base_path / "api/routes/agent_routes.py"
    if routes_file.exists():
        content = routes_file.read_text()
        expected_agents = [
            "sample_import",
            "full_text_analysis",
            "book_summary",
            "cross_book_synthesis"
        ]
        for agent in expected_agents:
            exists = agent in content
            status = "✓" if exists else "✗"
            print(f"  {status} {agent}")
            checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.append(False)

    # 检查requirements.txt的pyyaml
    print("\n3. 检查requirements.txt:")
    req_file = base_path / "requirements.txt"
    if req_file.exists():
        content = req_file.read_text()
        required = ["pyyaml", "litellm", "fastapi", "pydantic-settings"]
        for pkg in required:
            exists = pkg.lower() in content.lower()
            status = "✓" if exists else "✗"
            print(f"  {status} {pkg}")
            checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.append(False)

    return checks


def count_lines():
    """统计代码行数"""
    print("\n" + "=" * 70)
    print("代码行数统计")
    print("=" * 70)

    base_path = Path(__file__).parent
    files = [
        ("llm/client.py", "LLM客户端"),
        ("llm/prompt_builder.py", "Prompt构建器"),
        ("agents/full_text_analysis_agent.py", "全文分析Agent"),
        ("agents/book_summary_agent.py", "单书汇总Agent"),
        ("agents/cross_book_synthesis_agent.py", "跨书归纳Agent"),
        ("skills/skill_loader.py", "Skill加载器"),
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

    print(f"\n  {'Phase 2 新增代码总计':30s} : {total_lines:4d} 行")
    return total_lines


def main():
    """主函数"""
    print("\n" + "=" * 70)
    print("Phase 2 代码结构验证")
    print("=" * 70)

    # 检查文件存在性
    structure_results = check_directory_structure()

    # 检查文件内容
    content_results = check_file_content()

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

    print(f"新增代码量:   {total_lines} 行")

    all_passed = structure_passed == structure_total and content_passed == content_total

    print("\n" + "=" * 70)
    if all_passed:
        print("✓ 所有检查通过！Phase 2 代码结构完整")
    else:
        print("✗ 部分检查未通过，请检查上述错误")
    print("=" * 70)

    return all_passed


if __name__ == "__main__":
    import sys
    success = main()
    sys.exit(0 if success else 1)
