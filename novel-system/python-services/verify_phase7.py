"""Phase 7 代码结构验证"""
from pathlib import Path

def main():
    print("\n" + "=" * 70)
    print("Phase 7 完整性验证")
    print("=" * 70)
    
    base_path = Path(__file__).parent
    
    # 检查文件
    files = [
        ("schemas/graph_schemas.py", "图谱Schema定义"),
        ("agents/graph_builder_agent.py", "GraphBuilderAgent"),
    ]
    
    total_lines = 0
    all_exist = True
    
    print("\n文件检查:")
    for file_path, desc in files:
        full_path = base_path / file_path
        exists = full_path.exists()
        status = "✓" if exists else "✗"
        print(f"  {status} {desc:40s} {'存在' if exists else '不存在'}")
        if exists:
            lines = len(full_path.read_text().splitlines())
            total_lines += lines
            print(f"      代码行数: {lines}")
        else:
            all_exist = False
    
    print(f"\n总代码量: {total_lines} 行")
    
    print("\n" + "=" * 70)
    if all_exist:
        print("✓ 所有检查通过！Phase 7 代码结构完整")
    else:
        print("✗ 部分文件缺失")
    print("=" * 70)
    
    return all_exist

if __name__ == "__main__":
    import sys
    success = main()
    sys.exit(0 if success else 1)
