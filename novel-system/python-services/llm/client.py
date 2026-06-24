"""
LLM客户端
统一的LLM调用接口，支持多种模型
"""
import asyncio
from typing import Dict, Any, Optional
from litellm import acompletion
import json
from utils.logger import get_logger

class LLMClient:
    """LLM客户端"""

    def __init__(self, model_config: Optional[Dict] = None):
        self.logger = get_logger("LLMClient")
        self.model_config = model_config or self._get_default_config()

    def _get_default_config(self) -> Dict:
        """获取默认模型配置"""
        return {
            "model": "gpt-4",
            "temperature": 0.7,
            "max_tokens": 4000
        }

    async def generate(self, prompt: str,
                      response_format: Optional[str] = None,
                      **kwargs) -> Dict[str, Any]:
        """
        生成文本

        Args:
            prompt: 提示词
            response_format: 响应格式 (json/text)
            **kwargs: 额外参数

        Returns:
            {
                "content": str,
                "usage": {"prompt_tokens": int, "completion_tokens": int}
            }
        """
        messages = [{"role": "user", "content": prompt}]

        # 合并配置
        config = {**self.model_config, **kwargs}

        # 如果要求JSON格式
        if response_format == "json":
            config["response_format"] = {"type": "json_object"}
            # 在prompt中明确要求JSON
            messages[0]["content"] = f"{prompt}\n\n请以JSON格式返回结果。"

        try:
            self.logger.info(f"Calling LLM: {config['model']}")

            response = await acompletion(
                model=config["model"],
                messages=messages,
                temperature=config.get("temperature", 0.7),
                max_tokens=config.get("max_tokens", 4000),
                **config.get("extra_params", {})
            )

            result = {
                "content": response.choices[0].message.content,
                "usage": {
                    "prompt_tokens": response.usage.prompt_tokens,
                    "completion_tokens": response.usage.completion_tokens
                }
            }

            self.logger.info(f"LLM call successful. Tokens: {result['usage']}")
            return result

        except Exception as e:
            self.logger.error(f"LLM generation failed: {str(e)}")
            raise Exception(f"LLM生成失败: {str(e)}")

    async def generate_with_retry(self, prompt: str,
                                  max_retries: int = 3,
                                  **kwargs) -> Dict[str, Any]:
        """带重试的生成"""
        for attempt in range(max_retries):
            try:
                return await self.generate(prompt, **kwargs)
            except Exception as e:
                if attempt == max_retries - 1:
                    raise

                wait_time = 2 ** attempt  # 指数退避
                self.logger.warning(f"Retry {attempt + 1}/{max_retries} after {wait_time}s")
                await asyncio.sleep(wait_time)

    async def generate_batch(self, prompts: list, **kwargs) -> list:
        """批量生成"""
        tasks = [self.generate(prompt, **kwargs) for prompt in prompts]
        return await asyncio.gather(*tasks, return_exceptions=True)
