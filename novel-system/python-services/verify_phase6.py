"""
Phase 6 代码结构验证
验证记忆系统相关文件是否正确创建
"""
from pathlib import Path


def check_file_exists(file_path: str, description: str) -> bool:
    """检查文件是否存在"""
    path = Path(file_path)
    exists = path.exists()
    status = "✓" if exists else "✗"
    print(f"  {status} {description:50s} {'存在' if exists else '不存在'}")
    return exists


def check_phase6_structure():
    """检查Phase 6目录结构"""
    print("=" * 70)
    print("Phase 6 代码结构验证")
    print("=" * 70)

    base_path = Path(__file__).parent
    results = []

    # Schema定义
    print("\n1. Schema定义:")
    results.append(check_file_exists(
        base_path / "schemas/memory_schemas.py",
        "记忆Schema定义"
    ))

    # Memory Agents
    print("\n2. Memory Agents:")
    results.append(check_file_exists(
        base_path / "agents/memory_extractor_agent.py",
        "MemoryExtractorAgent实现"
    ))
    results.append(check_file_exists(
        base_path / "agents/memory_query_agent.py",
        "MemoryQueryAgent实现"
    ))

    # 更新的文件
    print("\n3. 更新的文件:")
    results.append(check_file_exists(
        base_path / "schemas/__init__.py",
        "Schemas包导出更新"
    ))
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

    # 检查memory_schemas.py的主要类
    print("\n1. 检查memory_schemas.py:")
    schemas_file = base_path / "schemas/memory_schemas.py"
    if schemas_file.exists():
        content = schemas_file.read_text()
        required_schemas = [
            "CharacterMemory",
            "WorldSettingMemory",
            "PlotMemory",
            "SuspenseMemory",
            "TimelineEvent",
            "MemoryExtractionRequest",
            "MemoryQueryRequest",
            "ContinuityCheckRequest"
        ]
        for schema in required_schemas:
            exists = schema in content
            status = "✓" if exists else "✗"
            print(f"  {status} {schema}")
            checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.extend([False] * 8)

    # 检查agents/__init__.py是否包含Memory Agents
    print("\n2. 检查agents/__init__.py:")
    agents_init = base_path / "agents/__init__.py"
    if agents_init.exists():
        content = agents_init.read_text()
        for agent in ["MemoryExtractorAgent", "MemoryQueryAgent"]:
            exists = agent in content
            status = "✓" if exists else "✗"
            print(f"  {status} {agent}已导出")
            checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.extend([False] * 2)

    # 检查agent_routes.py是否注册了memory agents
    print("\n3. 检查agent_routes.py:")
    routes_file = base_path / "api/routes/agent_routes.py"
    if routes_file.exists():
        content = routes_file.read_text()
        for agent_name in ["memory_extraction", "memory_query"]:
            exists = agent_name in content
            status = "✓" if exists else "✗"
            print(f"  {status} {agent_name}已注册")
            checks.append(exists)
    else:
        print("  ✗ 文件不存在")
        checks.extend([False] * 2)

    return checks


def count_lines():
    """统计代码行数"""
    print("\n" + "=" * 70)
    print("Phase 6 代码行数统计")
    print("=" * 70)

    base_path = Path(__file__).parent
    files = [
        ("schemas/memory_schemas.py", "记忆Schema定义"),
        ("agents/memory_extractor_agent.py", "MemoryExtractorAgent"),
        ("agents/memory_query_agent.py", "MemoryQueryAgent"),
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

    print(f"\n  {'Phase 6 新增代码总计':30s} : {total_lines:4d} 行")
    return total_lines


def check_agent_functionality():
    """检查Agent功能完整性"""
    print("\n" + "=" * 70)
    print("Agent功能完整性检查")
    print("=" * 70)

    base_path = Path(__file__).parent

    # 检查MemoryExtractorAgent
    print("\n1. MemoryExtractorAgent:")
    extractor_file = base_path / "agents/memory_extractor_agent.py"
    if extractor_file.exists():
        content = extractor_file.read_text()
        required_methods = [
            "_load_chapter",
            "_load_existing_memories",
            "_extract_memories",
            "_process_character_memory",
            "_process_setting_memory",
            "_save_memories"
        ]
        all_present = True
        for method in required_methods:
            exists = method in content
            status = "✓" if exists else "✗"
            print(f"  {status} {method}")
            if not exists:
                all_present = False
    else:
        print("  ✗ 文件不存在")
        all_present = False

    # 检查MemoryQueryAgent
    print("\n2. MemoryQueryAgent:")
    query_file = base_path / "agents/memory_query_agent.py"
    if query_file.exists():
        content = query_file.read_text()
        required_methods = [
            "_handle_query",
            "_handle_continuity_check",
            "_load_memories",
            "_query_memories"
        ]
        for method in required_methods:
            exists = method in content
            status = "✓" if exists else "✗"
            print(f"  {status} {method}")
            if not exists:
                all_present = False
    else:
        print("  ✗ 文件不存在")
        all_present = False

    return all_present


def main():
    """主函数"""
    print("\n" + "=" * 70)
    print("Phase 6 完整性验证")
    print("=" * 70)

    # 检查文件结构
    structure_results = check_phase6_structure()

    # 检查文件内容
    content_results = check_file_content()

    # 检查Agent功能
    agent_complete = check_agent_functionality()

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

    agent_status = "✓ 通过" if agent_complete else "✗ 未通过"
    print(f"Agent功能:    {agent_status}")

    print(f"新增代码量:   {total_lines} 行")

    all_passed = (
        structure_passed == structure_total and
        content_passed == content_total and
        agent_complete
    )

    print("\n" + "=" * 70)
    if all_passed:
        print("✓ 所有检查通过！Phase 6 代码结构完整")
    else:
        print("✗ 部分检查未通过，请检查上述错误")
    print("=" * 70)

    return all_passed


if __name__ == "__main__":
    import sys
    success = main()
    sys.exit(0 if success else 1)
