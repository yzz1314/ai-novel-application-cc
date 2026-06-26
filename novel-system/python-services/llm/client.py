"""
LLM客户端
统一的LLM调用接口，支持多种模型
"""
import asyncio
from contextlib import asynccontextmanager
from contextvars import ContextVar
from pathlib import Path
from typing import Dict, Any, Optional
from litellm import acompletion
import json
import re
from config import settings
from utils.logger import get_logger

_profile_context: ContextVar[Dict[str, Any]] = ContextVar("model_profile_context", default={})
_model_metadata_context: ContextVar[Dict[str, Any]] = ContextVar("model_metadata_context", default={})

TASK_MODEL_MAP = {
    "chunk_analysis": "mainModel",
    "full_text_analysis": "mainModel",
    "book_summary": "mainModel",
    "cross_book_synthesis": "mainModel",
    "skill_generation": "mainModel",
    "outline_generation": "mainModel",
    "chapter_writing": "mainModel",
    "chapter_revision": "mainModel",
    "chapter_review": "fastModel",
    "memory_ingest": "fastModel",
    "memory_extraction": "fastModel",
    "memory_query": "fastModel",
    "summary_generation": "fastModel",
    "embedding": "embeddingModel",
    "rerank": "rerankModel",
}

class LLMClient:
    """LLM客户端"""

    def __init__(self, model_config: Optional[Dict] = None):
        self.logger = get_logger("LLMClient")
        self.model_config = model_config or self._get_default_config()
        self.profile_store_path = Path(settings.PROJECT_BASE_PATH) / "config" / "model_profiles.json"

    def _get_default_config(self) -> Dict:
        """获取默认模型配置"""
        return {
            "model": settings.DEFAULT_MODEL,
            "temperature": 0.7,
            "max_tokens": 4000,
            "api_key": settings.OPENAI_API_KEY or None,
            "api_base": settings.OPENAI_API_BASE or None
        }

    @asynccontextmanager
    async def profile_context(self, profile_id: Optional[str], task_type: Optional[str] = None):
        """Set request-scoped model profile metadata for shared LLMClient usage."""
        token = _profile_context.set({
            "profile_id": profile_id,
            "task_type": task_type,
        })
        metadata_token = _model_metadata_context.set({})
        try:
            yield
        finally:
            _profile_context.reset(token)
            _model_metadata_context.reset(metadata_token)

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
        config = self._resolve_model_config(kwargs)
        self._set_model_metadata(config)
        if settings.MOCK_LLM or config.get("mock"):
            return self._mock_generate(prompt, response_format=response_format, config=config)

        messages = [{"role": "user", "content": prompt}]

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
                max_tokens=config.get("max_tokens", config.get("maxTokens", 4000)),
                api_key=config.get("api_key"),
                api_base=config.get("api_base"),
                **config.get("extra_params", {})
            )

            result = {
                "content": response.choices[0].message.content,
                "usage": {
                    "prompt_tokens": response.usage.prompt_tokens,
                    "completion_tokens": response.usage.completion_tokens,
                    "model_profile_id": config.get("model_profile_id"),
                    "model_role": config.get("model_role"),
                    "model": config.get("model")
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

    def current_model_metadata(self) -> Dict[str, Any]:
        return dict(_model_metadata_context.get() or {})

    def _set_model_metadata(self, config: Dict[str, Any]):
        _model_metadata_context.set({
            "model_profile_id": config.get("model_profile_id"),
            "model_role": config.get("model_role"),
            "model": config.get("model"),
            "mock": bool(config.get("mock") or settings.MOCK_LLM),
        })

    def _resolve_model_config(self, kwargs: Dict[str, Any]) -> Dict[str, Any]:
        context = _profile_context.get() or {}
        explicit_profile_id = kwargs.pop("model_profile_id", None)
        explicit_task_type = kwargs.pop("task_type", None)
        profile_id = explicit_profile_id or context.get("profile_id")
        task_type = explicit_task_type or context.get("task_type")
        profile_config = self._select_profile_model(profile_id, task_type)
        return {**self.model_config, **profile_config, **kwargs}

    def _select_profile_model(self, profile_id: Optional[str], task_type: Optional[str]) -> Dict[str, Any]:
        if not profile_id:
            return {}
        profile = self._load_profile(profile_id)
        if not profile:
            self.logger.warning(f"Model profile not found: {profile_id}; using default env config")
            return {}

        model_key = TASK_MODEL_MAP.get(task_type or "", "mainModel")
        model = profile.get(model_key) or profile.get("mainModel") or {}
        config = self._normalize_model_config(model)
        if config:
            config["model_profile_id"] = profile_id
            config["model_role"] = model_key
        return config

    def _load_profile(self, profile_id: str) -> Optional[Dict[str, Any]]:
        if not self.profile_store_path.exists():
            return None
        try:
            with self.profile_store_path.open("r", encoding="utf-8") as file:
                store = json.load(file)
        except Exception as exc:
            self.logger.warning(f"Failed to read model profile store: {exc}")
            return None
        for profile in store.get("profiles", []):
            if profile.get("profileId") == profile_id:
                return profile
        return None

    def _normalize_model_config(self, model: Dict[str, Any]) -> Dict[str, Any]:
        if not model:
            return {}
        provider = model.get("provider", "")
        normalized = {
            "model": model.get("model") or self.model_config.get("model"),
            "temperature": model.get("temperature", self.model_config.get("temperature", 0.7)),
            "max_tokens": model.get("max_tokens", model.get("maxTokens", self.model_config.get("max_tokens", 4000))),
            "api_key": model.get("api_key", model.get("apiKey") or self._provider_api_key(provider)),
            "api_base": model.get("api_base", model.get("endpoint") or self._provider_api_base(provider)),
            "mock": bool(model.get("mock") or provider == "mock"),
        }
        return {key: value for key, value in normalized.items() if value not in ("", None)}

    def _provider_api_key(self, provider: str) -> Optional[str]:
        if provider == "anthropic":
            return settings.ANTHROPIC_API_KEY or None
        return settings.OPENAI_API_KEY or None

    def _provider_api_base(self, provider: str) -> Optional[str]:
        if provider == "openai":
            return settings.OPENAI_API_BASE or None
        return None

    def _mock_generate(self, prompt: str, response_format: Optional[str] = None,
                       config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Deterministic local responses for integration tests and offline demos."""
        config = config or self._resolve_model_config({})
        if response_format == "json":
            content = json.dumps(self._mock_json_payload(prompt), ensure_ascii=False)
        else:
            content = self._mock_text_payload(prompt)

        prompt_tokens = max(1, len(prompt) // 4)
        completion_tokens = max(1, len(content) // 4)
        return {
            "content": content,
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "model_profile_id": config.get("model_profile_id"),
                "model_role": config.get("model_role"),
                "model": config.get("model", self.model_config.get("model"))
            }
        }

    def _mock_json_payload(self, prompt: str) -> Dict[str, Any]:
        if "片段摘要" in prompt and "scene_techniques" in prompt:
            return {
                "summary": self._extract_excerpt(prompt),
                "plot_function": "推进当前段落的核心矛盾，并保留后续悬念。",
                "reader_hook": "以未解问题和情绪压力牵引读者继续阅读。",
                "conflict": {
                    "external": "人物面临外部阻碍或敌对压力。",
                    "internal": "人物需要在退让与行动之间选择。",
                    "social": "环境规则或人际关系形成额外限制。"
                },
                "characters": [
                    {
                        "name": "主角",
                        "role_in_chunk": "承担行动视角",
                        "state_change": "从被动观察转向主动应对"
                    }
                ],
                "scene_techniques": [
                    {
                        "scene_type": "冲突",
                        "technique": "以动作和环境压迫开场",
                        "evidence_location": "段落前部"
                    }
                ],
                "prose_techniques": [
                    {
                        "technique_type": "短句推进",
                        "description": "使用较短句子制造节奏感。",
                        "evidence_location": "段落中部"
                    }
                ],
                "outline_techniques": [
                    {
                        "technique_type": "章末牵引",
                        "description": "在关键答案揭晓前停顿，制造下一段期待。"
                    }
                ],
                "suspense_and_foreshadowing": [
                    {
                        "type": "伏笔",
                        "description": "关键物件或异常反应预示后续事件。",
                        "related_entities": ["主角"]
                    }
                ],
                "appeal_points": [
                    {
                        "type": "危机压迫",
                        "description": "角色在压力下做出选择。",
                        "intensity": "中"
                    }
                ]
            }

        if "生成writing Skill" in prompt or '"prose_techniques"' in prompt:
            return {
                "prose_techniques": [
                    {
                        "index": 1,
                        "technique_name": "短句推进",
                        "frequency": 80.0,
                        "description": "用短句制造紧迫感。",
                        "usage_scenario": "冲突、追击、章末钩子",
                        "example": "门开了。风雪灌入。",
                        "suggestion": "在关键动作处减少解释。"
                    }
                ],
                "narrative_pace_description": "整体保持高压推进，穿插短暂信息释放。",
                "pace_points": ["冲突前置", "章末留钩", "信息分批揭示"],
                "scene_battle": [],
                "scene_daily": [],
                "scene_emotion": [],
                "conflict_techniques": [],
                "appeal_types": [{"type": "危机压迫", "count": 1, "percentage": 100.0}],
                "appeal_techniques": [],
                "must_keep": [{"feature": "悬念牵引", "reason": "维持追读"}],
                "flexible_elements": [{"element": "场景外壳", "guideline": "可随题材替换"}],
                "avoid_issues": [{"issue": "解释过早", "solution": "先行动后说明"}],
                "target_word_count": "2500-4000"
            }

        if "生成outline Skill" in prompt or '"word_count_range"' in prompt:
            return {
                "word_count_range": "80-150万字",
                "volume_count_range": "5-8卷",
                "words_per_volume": "10-20万字",
                "chapters_per_volume": "40-80章",
                "volume_pattern": "每卷围绕一个外部目标和一次人物跃迁展开。",
                "standard_chapter_length": 3000,
                "chapter_length_range": "2500-4000",
                "special_chapter_length": "4000-6000",
                "chapter_types": [{"type": "冲突推进", "percentage": 60.0, "description": "以目标阻碍推动章节"}],
                "rhythm_pattern_description": "快慢交替，章末保持牵引。",
                "fast_pace_ratio": 45.0,
                "fast_pace_techniques": ["动作前置", "短句推进"],
                "slow_pace_ratio": 25.0,
                "slow_pace_techniques": ["信息补足", "关系铺垫"],
                "transition_types": [{"type": "悬念转场", "count": 1, "percentage": 100.0}],
                "expected_appeal_interval": 3,
                "max_slow_chapters": 5,
                "max_fast_chapters": 8,
                "max_appeal_gap": 5
            }

        if "生成review Skill" in prompt or '"style_features"' in prompt:
            return {
                "style_features": [
                    {
                        "feature": "节奏紧凑",
                        "description": "少解释，多行动。",
                        "check_method": "检查段落是否服务冲突。",
                        "common_deviation": "解释性段落过长。",
                        "fix_suggestion": "拆分信息，延后说明。"
                    }
                ],
                "high_freq_techniques": [],
                "plot_issues": [],
                "character_issues": [],
                "writing_issues": [],
                "logic_issues": [],
                "avoid_elements": [],
                "pass_criteria": ["完成本章目标", "没有明显越界"],
                "good_criteria": ["冲突清晰", "章末有牵引"],
                "excellent_criteria": ["人物选择有代价", "爽点与伏笔兼具"]
            }

        if '"characters"' in prompt and "人物设定" in prompt:
            return {
                "characters": [
                    {
                        "name": "沈砚",
                        "role": "主角",
                        "description": "出身普通却意志坚定，擅长在危局中寻找破局点。",
                        "attributes": {"性格": "冷静坚韧", "能力": "洞察异常", "目标": "查清旧案", "弱点": "过度自责"},
                        "relationships": ["与师父有旧约"],
                        "introduction_chapter": 1
                    }
                ]
            }

        if '"world_settings"' in prompt and "世界观设定" in prompt:
            return {
                "world_settings": [
                    {
                        "name": "玄灯司",
                        "category": "组织",
                        "description": "负责追查异象与旧案的半隐秘机构。",
                        "related_entities": ["沈砚"]
                    }
                ]
            }

        if '"volumes"' in prompt and "分卷规划" in prompt:
            return {
                "volumes": [
                    {
                        "volume_number": 1,
                        "volume_title": "旧案初燃",
                        "target_word_count": 60000,
                        "target_chapters": 3,
                        "main_goal": "查明玉牌异动的来源",
                        "external_conflict": "旧案相关势力阻拦",
                        "character_growth": "从被动卷入到主动追查",
                        "key_points": ["玉牌发烫", "旧案重开", "发现线索"]
                    }
                ]
            }

        if '"chapters"' in prompt and "章节大纲" in prompt:
            return {
                "volume_title": "旧案初燃",
                "target_word_count": 60000,
                "target_chapters": 3,
                "main_goal": "查明玉牌异动的来源",
                "sub_goals": ["确认敌人身份"],
                "character_growth": "主角开始主动追查。",
                "external_conflict": "追查受到阻拦。",
                "internal_conflict": "主角担心牵连身边人。",
                "resolution": "找到第一条可靠线索。",
                "opening_node": {"chapter": 1, "description": "玉牌异动"},
                "quarter_node": {"chapter": 1, "description": "旧案线索出现"},
                "midpoint_node": {"chapter": 2, "description": "遭遇阻拦"},
                "three_quarter_node": {"chapter": 3, "description": "发现关键证据"},
                "climax_node": {"chapter": 3, "description": "卷末反转"},
                "new_suspense": ["玉牌为何发烫"],
                "resolved_suspense": [],
                "ongoing_suspense": ["旧案真相"],
                "major_appeal_points": [{"chapter": 2, "type": "危机破局", "description": "主角识破陷阱"}],
                "appeal_distribution": "每章一个明确推进点。",
                "new_characters": ["玄灯司使者"],
                "new_locations": ["旧案卷宗楼"],
                "new_settings": ["玉牌感应"],
                "chapters": [
                    self._mock_chapter_outline(1, "雪夜玉牌"),
                    self._mock_chapter_outline(2, "卷宗疑云"),
                    self._mock_chapter_outline(3, "暗线浮出")
                ]
            }

        if "审查以下章节内容" in prompt:
            return {
                "style_consistency": 8.0,
                "technique_usage": 7.5,
                "quality_level": 8.0,
                "continuity": 8.0,
                "structure": 8.0,
                "total_score": 39.5,
                "overall_rating": "pass",
                "issues": [],
                "suggestions": ["可继续强化章末牵引。"],
                "strengths": ["章节目标清晰。"],
                "pass_review": True,
                "needs_revision": False
            }

        if '"timeline_events"' in prompt and "提取关键信息" in prompt:
            return {
                "characters": [
                    {
                        "name": "沈砚",
                        "role": "protagonist",
                        "description": "主动追查旧案的主角。",
                        "current_status": {"位置": "城中", "情绪": "警觉"},
                        "status_changes": [],
                        "relationships": {},
                        "important_events": [{"event": "发现线索", "impact": "推进旧案"}]
                    }
                ],
                "world_settings": [],
                "plots": [
                    {
                        "plot_type": "main",
                        "title": "旧案追查",
                        "description": "主角开始追查旧案。",
                        "involved_characters": ["沈砚"],
                        "key_events": [{"event": "发现线索", "consequence": "进入下一阶段"}],
                        "status": "ongoing"
                    }
                ],
                "suspenses": [
                    {
                        "suspense_type": "medium",
                        "title": "玉牌异动",
                        "description": "玉牌异常发烫。",
                        "question": "它指向谁？",
                        "status": "active"
                    }
                ],
                "timeline_events": [
                    {
                        "event_type": "discovery",
                        "title": "发现线索",
                        "description": "主角获得旧案线索。",
                        "involved_characters": ["沈砚"],
                        "consequences": ["追查继续"]
                    }
                ]
            }

        return {"summary": "mock response"}

    def _mock_text_payload(self, prompt: str) -> str:
        if "单书分析报告" in prompt:
            title = self._extract_after(prompt, "书名：", "未知")
            return f"""# 《{title}》分析报告

## 一、整体概况
本报告由本地 mock LLM 生成，用于验证分析链路。样本具备清晰冲突、悬念牵引和章末推进。

## 二、作者风格特征
以短句推进和危机压迫为主，信息释放较克制。

## 三、情节设计
段落围绕外部阻碍、人物选择和未解问题展开。

## 四、可迁移技巧总结
- 冲突前置
- 短句推进
- 章末牵引
"""

        if "样本书籍写作规律归纳" in prompt or "跨书归纳" in prompt:
            return """# 样本书籍写作规律归纳

## 一、作者风格总结
样本共同强调冲突前置、悬念延迟和角色在压力中的选择。

## 二、类型规律总结
章节通常以外部阻碍开局，中段推进线索，末尾保留新问题。

## 三、可迁移技巧矩阵
- 冲突前置：适合开章和转场
- 章末牵引：适合制造追读
- 信息分批揭示：适合减少说明感

## 四、创作建议
保留强目标和强牵引，避免长篇静态解释。
"""

        if "请直接输出章节正文" in prompt:
            title = self._extract_after(prompt, "标题：", "本章")
            return (
                f"{title}的第一缕风从门缝里挤进来。\n\n"
                "沈砚按住发烫的玉牌，指节一点点收紧。卷宗楼的灯还亮着，"
                "可守夜人已经不见了。\n\n"
                "他没有喊人。旧案两个字像压在舌根的铁，谁先说出口，谁就会被卷进去。\n\n"
                "楼上传来一声轻响。\n\n"
                "沈砚抬头，看见尘封十年的案卷，正从架上缓缓滑落。"
            )

        return "这是 mock LLM 生成的本地联调文本。"

    def _mock_chapter_outline(self, chapter_number: int, title: str) -> Dict[str, Any]:
        return {
            "chapter_number": chapter_number,
            "chapter_title": title,
            "target_word_count": 3000,
            "plot_goal": "推进旧案线索并制造新的悬念",
            "character_development": "主角从观察转向行动",
            "info_reveal": "玉牌与旧案存在联系",
            "scenes": [{"scene_name": "卷宗楼", "word_count": 1000, "pace": "fast"}],
            "conflict": "有人试图阻止主角接近真相",
            "appeal_point": "主角识破异常",
            "suspense": "幕后之人身份未明",
            "connect_previous": "承接玉牌异动",
            "lead_to_next": "发现新线索",
            "pace_type": "fast",
            "info_density": "medium"
        }

    def _extract_excerpt(self, prompt: str) -> str:
        match = re.search(r"内容：\s*(.*?)\n\s*## 输出要求", prompt, re.S)
        text = re.sub(r"\s+", " ", match.group(1)).strip() if match else ""
        return (text[:80] + "...") if len(text) > 80 else text or "片段围绕冲突和悬念推进。"

    def _extract_after(self, prompt: str, label: str, default: str) -> str:
        for line in prompt.splitlines():
            if label in line:
                return line.split(label, 1)[1].strip().strip("- ").strip() or default
        return default
