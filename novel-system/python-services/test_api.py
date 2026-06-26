"""
测试第三方API配置
"""
import asyncio
import sys
from pathlib import Path

# 添加项目路径
sys.path.insert(0, str(Path(__file__).parent))

from llm.client import LLMClient
from config import settings


async def test_basic_call():
    """测试基本调用"""
    print("=" * 60)
    print("测试1: 基本API调用")
    print("=" * 60)

    print(f"使用模型: {settings.DEFAULT_MODEL}")

    client = LLMClient()

    try:
        result = await client.generate("你好，请回复'测试成功'")
        print(f"✅ API调用成功！")
        print(f"响应: {result['content']}")
        print(f"Token使用: prompt={result['usage']['prompt_tokens']}, completion={result['usage']['completion_tokens']}")
        return True
    except Exception as e:
        print(f"❌ API调用失败: {str(e)}")
        return False


async def test_json_response():
    """测试JSON格式响应"""
    print("\n" + "=" * 60)
    print("测试2: JSON格式响应")
    print("=" * 60)

    client = LLMClient()

    prompt = """
请以JSON格式返回以下信息：
- name: 张三
- age: 25
- skills: [Python, Java]
"""

    try:
        result = await client.generate(prompt, response_format="json")
        print(f"✅ JSON响应成功！")
        print(f"响应: {result['content']}")
        return True
    except Exception as e:
        print(f"❌ JSON响应失败: {str(e)}")
        return False


async def test_retry_mechanism():
    """测试重试机制"""
    print("\n" + "=" * 60)
    print("测试3: 重试机制")
    print("=" * 60)

    client = LLMClient()

    try:
        result = await client.generate_with_retry(
            "请回复'重试机制正常'",
            max_retries=3
        )
        print(f"✅ 重试机制正常！")
        print(f"响应: {result['content']}")
        return True
    except Exception as e:
        print(f"❌ 重试机制测试失败: {str(e)}")
        return False


async def test_long_prompt():
    """测试长提示词"""
    print("\n" + "=" * 60)
    print("测试4: 长提示词处理")
    print("=" * 60)

    client = LLMClient()

    long_prompt = "请概括以下内容：\n" + "这是一段很长的文本。" * 100

    try:
        result = await client.generate(long_prompt)
        print(f"✅ 长提示词处理成功！")
        print(f"响应长度: {len(result['content'])} 字符")
        print(f"Token使用: {result['usage']}")
        return True
    except Exception as e:
        print(f"❌ 长提示词处理失败: {str(e)}")
        return False


async def test_cost_estimation():
    """测试成本估算"""
    print("\n" + "=" * 60)
    print("测试5: 成本估算")
    print("=" * 60)

    client = LLMClient()

    try:
        result = await client.generate("请说明AI小说创作的优势")

        # 估算成本（GPT-4价格：$0.03/1K prompt tokens, $0.06/1K completion tokens）
        # GPT-3.5-turbo价格：$0.0015/1K prompt tokens, $0.002/1K completion tokens

        prompt_tokens = result['usage']['prompt_tokens']
        completion_tokens = result['usage']['completion_tokens']

        # 假设使用GPT-3.5-turbo
        cost_gpt35 = (prompt_tokens * 0.0015 + completion_tokens * 0.002) / 1000

        # 假设使用GPT-4
        cost_gpt4 = (prompt_tokens * 0.03 + completion_tokens * 0.06) / 1000

        print(f"✅ 成本估算成功！")
        print(f"Token使用: prompt={prompt_tokens}, completion={completion_tokens}")
        print(f"如果使用GPT-3.5-turbo: ${cost_gpt35:.4f}")
        print(f"如果使用GPT-4: ${cost_gpt4:.4f}")
        print(f"GPT-4成本是GPT-3.5的 {cost_gpt4/cost_gpt35:.1f}x")

        return True
    except Exception as e:
        print(f"❌ 成本估算失败: {str(e)}")
        return False


async def main():
    """主测试函数"""
    print("\n")
    print("╔" + "═" * 58 + "╗")
    print("║" + " " * 15 + "第三方API配置测试" + " " * 25 + "║")
    print("╚" + "═" * 58 + "╝")
    print("\n")

    # 显示配置信息
    print("当前配置:")
    print(f"  模型: {settings.DEFAULT_MODEL}")
    print(f"  API密钥: {'已配置' if settings.OPENAI_API_KEY else '未配置'}")
    print(f"  日志级别: {settings.LOG_LEVEL}")
    print()

    if not settings.OPENAI_API_KEY:
        print("❌ 错误: 未配置API密钥")
        print("请在 .env 文件中设置 OPENAI_API_KEY")
        return

    # 运行测试
    results = []

    results.append(await test_basic_call())
    results.append(await test_json_response())
    results.append(await test_retry_mechanism())
    results.append(await test_long_prompt())
    results.append(await test_cost_estimation())

    # 总结
    print("\n" + "=" * 60)
    print("测试总结")
    print("=" * 60)

    passed = sum(results)
    total = len(results)

    print(f"通过: {passed}/{total}")

    if passed == total:
        print("✅ 所有测试通过！你的API配置正确。")
    else:
        print(f"⚠️  有 {total - passed} 个测试失败，请检查配置。")

    print("\n配置建议:")
    print("  1. 使用 GPT-3.5-turbo 可降低90%成本")
    print("  2. 设置 MAX_CONCURRENT_TASKS=2 避免限流")
    print("  3. 设置 DAILY_COST_LIMIT 控制每日成本")
    print()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n\n测试已取消")
    except Exception as e:
        print(f"\n\n❌ 测试出错: {str(e)}")
