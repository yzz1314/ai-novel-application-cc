"""
Phase 2 功能验证测试
验证所有新实现的模块能否正常导入和初始化
"""
import sys
import asyncio
from pathlib import Path

# 添加路径
sys.path.insert(0, str(Path(__file__).parent))


def test_imports():
    """测试所有模块导入"""
    print("=" * 60)
    print("测试模块导入...")
    print("=" * 60)

    try:
        # 测试LLM模块
        print("\n1. 测试LLM模块...")
        from llm.client import LLMClient
        from llm.prompt_builder import PromptBuilder
        print("   ✓ LLMClient")
        print("   ✓ PromptBuilder")

        # 测试Skills模块
        print("\n2. 测试Skills模块...")
        from skills.skill_loader import SkillLoader
        print("   ✓ SkillLoader")

        # 测试Agents模块
        print("\n3. 测试Agents模块...")
        from agents import (
            BaseAgent,
            SampleImportAgent,
            FullTextAnalysisAgent,
            BookSummaryAgent,
            CrossBookSynthesisAgent
        )
        print("   ✓ BaseAgent")
        print("   ✓ SampleImportAgent")
        print("   ✓ FullTextAnalysisAgent")
        print("   ✓ BookSummaryAgent")
        print("   ✓ CrossBookSynthesisAgent")

        # 测试API路由
        print("\n4. 测试API路由...")
        from api.routes.agent_routes import router, AGENT_REGISTRY
        print(f"   ✓ Agent路由已加载")
        print(f"   ✓ 已注册{len(AGENT_REGISTRY)}个Agent: {list(AGENT_REGISTRY.keys())}")

        print("\n" + "=" * 60)
        print("✓ 所有模块导入成功!")
        print("=" * 60)
        return True

    except Exception as e:
        print(f"\n✗ 导入失败: {str(e)}")
        import traceback
        traceback.print_exc()
        return False


def test_agent_initialization():
    """测试Agent初始化"""
    print("\n" + "=" * 60)
    print("测试Agent初始化...")
    print("=" * 60)

    try:
        from agents import (
            SampleImportAgent,
            FullTextAnalysisAgent,
            BookSummaryAgent,
            CrossBookSynthesisAgent
        )

        # 测试每个Agent
        agents = [
            ("SampleImportAgent", SampleImportAgent),
            ("FullTextAnalysisAgent", FullTextAnalysisAgent),
            ("BookSummaryAgent", BookSummaryAgent),
            ("CrossBookSynthesisAgent", CrossBookSynthesisAgent)
        ]

        for name, AgentClass in agents:
            try:
                agent = AgentClass()
                print(f"\n{name}:")
                print(f"   - agent_name: {agent.agent_name}")
                print(f"   - supported_tasks: {agent.supported_tasks}")
                print(f"   ✓ 初始化成功")
            except Exception as e:
                print(f"   ✗ 初始化失败: {str(e)}")
                raise

        print("\n" + "=" * 60)
        print("✓ 所有Agent初始化成功!")
        print("=" * 60)
        return True

    except Exception as e:
        print(f"\n✗ Agent初始化失败: {str(e)}")
        import traceback
        traceback.print_exc()
        return False


def test_llm_client():
    """测试LLM客户端"""
    print("\n" + "=" * 60)
    print("测试LLM客户端...")
    print("=" * 60)

    try:
        from llm.client import LLMClient

        client = LLMClient()
        print(f"\n默认配置:")
        print(f"   - model: {client.model_config.get('model')}")
        print(f"   - temperature: {client.model_config.get('temperature')}")
        print(f"   - max_tokens: {client.model_config.get('max_tokens')}")
        print(f"   ✓ LLMClient初始化成功")

        print("\n" + "=" * 60)
        print("✓ LLM客户端测试通过!")
        print("=" * 60)
        return True

    except Exception as e:
        print(f"\n✗ LLM客户端测试失败: {str(e)}")
        import traceback
        traceback.print_exc()
        return False


def test_prompt_builder():
    """测试Prompt构建器"""
    print("\n" + "=" * 60)
    print("测试Prompt构建器...")
    print("=" * 60)

    try:
        from llm.prompt_builder import PromptBuilder

        builder = PromptBuilder()

        # 测试chunk分析prompt
        chunk_text = "这是一段测试文本。"
        chapter_range = "第1章"
        prompt = builder.build_chunk_analysis_prompt(chunk_text, chapter_range)
        print(f"\n✓ Chunk分析Prompt生成成功 (长度: {len(prompt)}字符)")

        # 测试单书汇总prompt
        book_info = {"title": "测试小说", "total_chars": 100000, "total_chapters": 100}
        prompt = builder.build_book_summary_prompt([], book_info)
        print(f"✓ 单书汇总Prompt生成成功 (长度: {len(prompt)}字符)")

        # 测试跨书归纳prompt
        prompt = builder.build_cross_book_synthesis_prompt([])
        print(f"✓ 跨书归纳Prompt生成成功 (长度: {len(prompt)}字符)")

        print("\n" + "=" * 60)
        print("✓ Prompt构建器测试通过!")
        print("=" * 60)
        return True

    except Exception as e:
        print(f"\n✗ Prompt构建器测试失败: {str(e)}")
        import traceback
        traceback.print_exc()
        return False


def test_skill_loader():
    """测试Skill加载器"""
    print("\n" + "=" * 60)
    print("测试Skill加载器...")
    print("=" * 60)

    try:
        from skills.skill_loader import SkillLoader

        loader = SkillLoader()
        print(f"\n✓ SkillLoader初始化成功")

        # 测试模板生成
        template = loader.create_skill_template(
            "test_skill",
            "测试Skill",
            "这是一个测试Skill"
        )
        print(f"✓ Skill模板生成成功 (长度: {len(template)}字符)")

        print("\n" + "=" * 60)
        print("✓ Skill加载器测试通过!")
        print("=" * 60)
        return True

    except Exception as e:
        print(f"\n✗ Skill加载器测试失败: {str(e)}")
        import traceback
        traceback.print_exc()
        return False


def main():
    """运行所有测试"""
    print("\n" + "=" * 60)
    print("Phase 2 功能验证测试")
    print("=" * 60)

    results = []

    # 运行测试
    results.append(("模块导入", test_imports()))
    results.append(("Agent初始化", test_agent_initialization()))
    results.append(("LLM客户端", test_llm_client()))
    results.append(("Prompt构建器", test_prompt_builder()))
    results.append(("Skill加载器", test_skill_loader()))

    # 汇总结果
    print("\n" + "=" * 60)
    print("测试结果汇总")
    print("=" * 60)

    for name, result in results:
        status = "✓ 通过" if result else "✗ 失败"
        print(f"{name:20s} : {status}")

    passed = sum(1 for _, result in results if result)
    total = len(results)

    print("\n" + "=" * 60)
    print(f"总计: {passed}/{total} 通过")
    print("=" * 60)

    return passed == total


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
