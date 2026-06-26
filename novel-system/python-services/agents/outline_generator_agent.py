"""
大纲生成Agent
基于用户需求和项目Skills生成完整的书籍大纲
"""
import json
import uuid
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from schemas.outline_schemas import (
    BookOutlineSchema,
    VolumeOutlineSchema,
    ChapterOutlineSchema,
    CharacterSchema,
    WorldSettingSchema,
    OutlineGenerationRequest
)
from llm.client import LLMClient
from skills.skill_loader import SkillLoader
from quality.outline_boundary import OutlineBoundaryCompleter
from config import settings
from utils.logger import get_logger


class OutlineGeneratorAgent(BaseAgent):
    """大纲生成Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("OutlineGeneratorAgent")
        self.supported_tasks = ["outline_generation"]
        self.llm_client = llm_client or LLMClient()
        self.skill_loader = SkillLoader()
        self.boundary_completer = OutlineBoundaryCompleter()
        self.logger = get_logger("OutlineGeneratorAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行大纲生成任务

        流程：
        1. 解析用户需求
        2. 加载项目Skills
        3. 生成整体框架
        4. 生成分卷规划
        5. 生成章节大纲
        6. 验证和保存
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)

            # 2. 解析生成需求
            gen_request = OutlineGenerationRequest(**request.parameters)
            self.logger.info(f"Generating outline for: {gen_request.book_title}")

            # 3. 加载项目Skills
            skills_content = await self._load_project_skills(
                gen_request.project_id,
                gen_request.use_project_skills,
                gen_request.custom_skill_names
            )
            project_soul_content = await self._load_project_soul(gen_request.project_id)
            if project_soul_content:
                skills_content["__project_soul__"] = project_soul_content

            # 4. 生成书籍基本信息
            book_id = f"book_{uuid.uuid4().hex[:8]}"
            book_outline = BookOutlineSchema(
                book_id=book_id,
                project_id=gen_request.project_id,
                book_title=gen_request.book_title,
                genre=gen_request.genre,
                target_word_count=gen_request.target_word_count,
                core_concept=gen_request.core_concept,
                world_view=gen_request.world_view,
                main_conflict=gen_request.main_conflict,
                used_skills=[
                    skill_name
                    for skill_name in skills_content.keys()
                    if not skill_name.startswith("__")
                ]
            )

            # 5. 生成人物设定
            characters = await self._generate_characters(
                gen_request, skills_content
            )
            book_outline.characters = characters

            # 6. 生成世界观设定
            world_settings = await self._generate_world_settings(
                gen_request, skills_content
            )
            book_outline.world_settings = world_settings

            # 7. 生成整体框架（分卷规划）
            volumes_plan = await self._generate_volumes_plan(
                gen_request, skills_content, characters
            )

            # 8. 为每卷生成详细章节大纲
            volumes = []
            for vol_idx, vol_plan in enumerate(volumes_plan, 1):
                self.logger.info(f"Generating chapters for volume {vol_idx}...")

                volume = await self._generate_volume_outline(
                    vol_idx, vol_plan, gen_request, skills_content, characters
                )
                volumes.append(volume)

            book_outline.volumes = volumes
            book_outline.total_volumes = len(volumes)
            book_outline.total_chapters = sum(len(v.chapters) for v in volumes)
            boundary_completion = self.boundary_completer.complete_outline(book_outline)

            # 9. 生成整体节奏曲线和长线悬念
            book_outline.rhythm_curve = await self._generate_rhythm_curve(
                volumes, skills_content
            )
            book_outline.long_term_suspense = await self._generate_long_suspense(
                volumes, skills_content
            )

            # 10. 验证大纲
            validation_result = await self._validate_outline(book_outline, skills_content)

            # 11. 保存大纲
            outline_path = await self._save_outline(book_outline)
            project_soul_path = await self._save_project_soul(book_outline)

            # 12. 构建响应
            output_refs = [str(outline_path), str(project_soul_path)]

            structured_output = {
                "book_id": book_id,
                "book_title": gen_request.book_title,
                "total_volumes": book_outline.total_volumes,
                "total_chapters": book_outline.total_chapters,
                "outline_path": str(outline_path),
                "project_soul_path": str(project_soul_path),
                "boundary_completion": boundary_completion,
                "validation_result": validation_result,
                "created_at": datetime.now().isoformat()
            }

            return self._build_response(
                request=request,
                status="success",
                output_refs=output_refs,
                structured_output=structured_output
            )

        except Exception as e:
            self.logger.error(f"Outline generation failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "OUTLINE_GENERATION_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    async def _load_project_skills(self, project_id: str, use_project_skills: bool,
                                   custom_skill_names: List[str]) -> Dict[str, str]:
        """加载项目Skills"""
        skills_content = {}

        if use_project_skills:
            # 加载项目生成的Skills
            project_skills_dirs = [
                Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "skills" / "local",
                Path(settings.SKILLS_PATH) / project_id,
            ]
            for project_skills_dir in project_skills_dirs:
                if not project_skills_dir.exists():
                    continue
                for skill_file in project_skills_dir.glob("*_skill.md"):
                    skill_name = skill_file.stem
                    if skill_name in skills_content:
                        continue
                    with open(skill_file, 'r', encoding='utf-8') as f:
                        skills_content[skill_name] = f.read()
                    self.logger.info(f"Loaded project skill: {skill_name}")

        # 加载自定义Skills
        for skill_name in custom_skill_names:
            try:
                skill = self.skill_loader.load_skill(skill_name)
                skills_content[skill_name] = skill["content"]
                self.logger.info(f"Loaded custom skill: {skill_name}")
            except Exception as e:
                self.logger.warning(f"Failed to load skill {skill_name}: {str(e)}")

        if not skills_content:
            self.logger.warning("No skills loaded, using default prompts")

        return skills_content

    async def _load_project_soul(self, project_id: str) -> str:
        """加载已有项目灵魂文档。"""
        soul_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "novel" / "soul" / "project_soul.md"
        )
        if not soul_file.exists():
            return ""

        with open(soul_file, 'r', encoding='utf-8') as f:
            return f.read()

    def _project_soul_prompt_section(self, skills_content: Dict[str, str]) -> str:
        """构建Project Soul prompt注入片段。"""
        project_soul = skills_content.get("__project_soul__", "").strip()
        if not project_soul:
            return ""

        return f"""
## 项目灵魂（不可违背的核心设定）

{project_soul}

**以上设定在任何情况下都不得违背。**
"""

    async def _generate_characters(self, gen_request: OutlineGenerationRequest,
                                   skills_content: Dict[str, str]) -> List[CharacterSchema]:
        """生成人物设定"""
        self.logger.info("Generating character settings...")

        # 构建prompt
        prompt = self._build_character_generation_prompt(gen_request, skills_content)

        # 调用LLM
        llm_response = await self.llm_client.generate_with_retry(
            prompt=prompt,
            response_format="json",
            max_retries=3,
            max_tokens=4000
        )

        # 更新指标
        self.metrics["llm_calls"] += 1
        self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
        self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

        # 解析结果
        characters_data = json.loads(llm_response["content"])
        characters = [CharacterSchema(**char) for char in characters_data.get("characters", [])]

        self.logger.info(f"Generated {len(characters)} characters")
        return characters

    async def _generate_world_settings(self, gen_request: OutlineGenerationRequest,
                                       skills_content: Dict[str, str]) -> List[WorldSettingSchema]:
        """生成世界观设定"""
        self.logger.info("Generating world settings...")

        # 构建prompt
        prompt = self._build_world_generation_prompt(gen_request, skills_content)

        # 调用LLM
        llm_response = await self.llm_client.generate_with_retry(
            prompt=prompt,
            response_format="json",
            max_retries=3,
            max_tokens=3000
        )

        # 更新指标
        self.metrics["llm_calls"] += 1
        self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
        self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

        # 解析结果
        settings_data = json.loads(llm_response["content"])
        world_settings = [
            WorldSettingSchema(**setting)
            for setting in settings_data.get("world_settings", [])
        ]

        self.logger.info(f"Generated {len(world_settings)} world settings")
        return world_settings

    async def _generate_volumes_plan(self, gen_request: OutlineGenerationRequest,
                                     skills_content: Dict[str, str],
                                     characters: List[CharacterSchema]) -> List[Dict]:
        """生成分卷规划"""
        self.logger.info("Generating volumes plan...")

        # 构建prompt
        prompt = self._build_volumes_plan_prompt(gen_request, skills_content, characters)

        # 调用LLM
        llm_response = await self.llm_client.generate_with_retry(
            prompt=prompt,
            response_format="json",
            max_retries=3,
            max_tokens=6000
        )

        # 更新指标
        self.metrics["llm_calls"] += 1
        self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
        self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

        # 解析结果
        volumes_data = json.loads(llm_response["content"])
        volumes_plan = volumes_data.get("volumes", [])

        self.logger.info(f"Generated plan for {len(volumes_plan)} volumes")
        return volumes_plan

    async def _generate_volume_outline(self, volume_number: int, vol_plan: Dict,
                                       gen_request: OutlineGenerationRequest,
                                       skills_content: Dict[str, str],
                                       characters: List[CharacterSchema]) -> VolumeOutlineSchema:
        """生成单卷详细大纲"""
        self.logger.info(f"Generating outline for volume {volume_number}...")

        # 构建prompt
        prompt = self._build_volume_outline_prompt(
            volume_number, vol_plan, gen_request, skills_content, characters
        )

        # 调用LLM
        llm_response = await self.llm_client.generate_with_retry(
            prompt=prompt,
            response_format="json",
            max_retries=3,
            max_tokens=8000
        )

        # 更新指标
        self.metrics["llm_calls"] += 1
        self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
        self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

        # 解析结果
        volume_data = json.loads(llm_response["content"])

        # 构建VolumeOutlineSchema
        volume = VolumeOutlineSchema(
            volume_number=volume_number,
            volume_title=volume_data.get("volume_title", f"第{volume_number}卷"),
            target_word_count=volume_data.get("target_word_count", 100000),
            target_chapters=volume_data.get("target_chapters", 50),
            main_goal=volume_data.get("main_goal", ""),
            sub_goals=volume_data.get("sub_goals", []),
            character_growth=volume_data.get("character_growth", ""),
            external_conflict=volume_data.get("external_conflict", ""),
            internal_conflict=volume_data.get("internal_conflict", ""),
            resolution=volume_data.get("resolution", ""),
            opening_node=volume_data.get("opening_node", {}),
            quarter_node=volume_data.get("quarter_node", {}),
            midpoint_node=volume_data.get("midpoint_node", {}),
            three_quarter_node=volume_data.get("three_quarter_node", {}),
            climax_node=volume_data.get("climax_node", {}),
            new_suspense=volume_data.get("new_suspense", []),
            resolved_suspense=volume_data.get("resolved_suspense", []),
            ongoing_suspense=volume_data.get("ongoing_suspense", []),
            major_appeal_points=volume_data.get("major_appeal_points", []),
            appeal_distribution=volume_data.get("appeal_distribution", ""),
            new_characters=volume_data.get("new_characters", []),
            new_locations=volume_data.get("new_locations", []),
            new_settings=volume_data.get("new_settings", [])
        )

        # 生成章节大纲
        chapters_data = volume_data.get("chapters", [])
        chapters = [ChapterOutlineSchema(**ch) for ch in chapters_data]
        volume.chapters = chapters

        self.logger.info(f"Volume {volume_number} generated with {len(chapters)} chapters")
        return volume

    def _build_character_generation_prompt(self, gen_request: OutlineGenerationRequest,
                                           skills_content: Dict[str, str]) -> str:
        """构建人物生成prompt"""

        # 提取Skills中的人物塑造部分
        character_guidance = ""
        if "writing_skill" in skills_content:
            # 简单提取，实际可以更智能
            character_guidance = "参考项目Skills中的人物塑造规律"

        user_character_section = (
            f"## 用户提供的人物信息\n{gen_request.main_characters}"
            if gen_request.main_characters else ""
        )
        project_soul_section = self._project_soul_prompt_section(skills_content)

        prompt = f"""
你是一位专业的小说策划师。请为以下小说生成主要人物设定。

{project_soul_section}

## 小说基本信息

- 书名：{gen_request.book_title}
- 类型：{gen_request.genre}
- 核心概念：{gen_request.core_concept}
- 世界观：{gen_request.world_view}
- 主要冲突：{gen_request.main_conflict}

{user_character_section}

## 人物设定要求

请生成3-8个主要人物，包括：
1. 主角（1-2人）
2. 重要配角（2-4人）
3. 主要反派（1-2人）

{character_guidance}

## 输出格式

请以JSON格式返回：

```json
{{
  "characters": [
    {{
      "name": "人物名称",
      "role": "主角/配角/反派",
      "description": "人物描述（100-200字）",
      "attributes": {{
        "性格": "性格描述",
        "能力": "能力描述",
        "目标": "人物目标",
        "弱点": "人物弱点"
      }},
      "relationships": ["与其他人物的关系"],
      "introduction_chapter": 1
    }}
  ]
}}
```

注意：
1. 人物要有鲜明的特点
2. 角色之间要有复杂的关系网
3. 人物设定要符合类型和世界观
"""
        return prompt

    def _build_world_generation_prompt(self, gen_request: OutlineGenerationRequest,
                                       skills_content: Dict[str, str]) -> str:
        """构建世界观生成prompt"""
        project_soul_section = self._project_soul_prompt_section(skills_content)

        prompt = f"""
你是一位专业的小说策划师。请为以下小说生成世界观设定。

{project_soul_section}

## 小说基本信息

- 书名：{gen_request.book_title}
- 类型：{gen_request.genre}
- 世界观：{gen_request.world_view}

## 世界观设定要求

请生成以下类别的设定：
1. 地点设定（3-5个主要地点）
2. 组织/势力（2-4个）
3. 规则/体系（如修炼体系、魔法体系等）
4. 重要物品/技能

## 输出格式

请以JSON格式返回：

```json
{{
  "world_settings": [
    {{
      "name": "设定名称",
      "category": "地点/组织/规则/物品/技能",
      "description": "详细描述（100-300字）",
      "related_entities": ["相关人物或其他设定"]
    }}
  ]
}}
```
"""
        return prompt

    def _build_volumes_plan_prompt(self, gen_request: OutlineGenerationRequest,
                                   skills_content: Dict[str, str],
                                   characters: List[CharacterSchema]) -> str:
        """构建分卷规划prompt"""

        # 提取大纲Skill中的结构指导
        structure_guidance = ""
        if "outline_skill" in skills_content:
            structure_guidance = "参考项目Skills中的分卷结构规律"
        project_soul_section = self._project_soul_prompt_section(skills_content)

        characters_summary = "\n".join([
            f"- {ch.name}（{ch.role}）：{ch.description[:50]}..."
            for ch in characters[:5]
        ])

        prompt = f"""
你是一位专业的小说策划师。请为以下小说规划分卷结构。

{project_soul_section}

## 小说基本信息

- 书名：{gen_request.book_title}
- 类型：{gen_request.genre}
- 目标字数：{gen_request.target_word_count}字
- 目标卷数：{gen_request.target_volumes}卷
- 核心概念：{gen_request.core_concept}
- 主要冲突：{gen_request.main_conflict}

## 主要人物

{characters_summary}

{structure_guidance}

## 分卷规划要求

为每一卷规划：
1. 卷标题和定位
2. 本卷的核心目标和冲突
3. 人物成长轨迹
4. 关键剧情节点
5. 字数和章节数

## 输出格式

请以JSON格式返回：

```json
{{
  "volumes": [
    {{
      "volume_number": 1,
      "volume_title": "卷标题",
      "target_word_count": 200000,
      "target_chapters": 100,
      "main_goal": "本卷主要目标",
      "external_conflict": "外部冲突",
      "character_growth": "人物成长",
      "key_points": ["关键剧情点1", "关键剧情点2"]
    }}
  ]
}}
```

注意：
1. 每卷要有明确的目标和冲突
2. 卷与卷之间要有连贯性
3. 节奏要符合类型规律
"""
        return prompt

    def _build_volume_outline_prompt(self, volume_number: int, vol_plan: Dict,
                                     gen_request: OutlineGenerationRequest,
                                     skills_content: Dict[str, str],
                                     characters: List[CharacterSchema]) -> str:
        """构建单卷详细大纲prompt"""
        project_soul_section = self._project_soul_prompt_section(skills_content)

        prompt = f"""
你是一位专业的小说策划师。请为第{volume_number}卷生成详细的章节大纲。

{project_soul_section}

## 本卷规划

- 卷标题：{vol_plan.get('volume_title', f'第{volume_number}卷')}
- 目标字数：{vol_plan.get('target_word_count', 100000)}字
- 目标章节：{vol_plan.get('target_chapters', 50)}章
- 核心目标：{vol_plan.get('main_goal', '')}
- 主要冲突：{vol_plan.get('external_conflict', '')}

## 章节大纲要求

为本卷的每一章生成大纲，包括：
1. 章节标题
2. 本章目标（剧情推进、人物发展、信息揭示）
3. 场景设计
4. 冲突和爽点
5. 悬念伏笔
6. 节奏和信息密度
7. 章节边界控制，必须明确本章只能写什么、可以轻铺什么、禁止提前写什么、停止点和章末钩子

章节数量：{vol_plan.get('target_chapters', 50)}章

## 输出格式（只返回前10章的详细大纲，其余章节只返回标题和目标）

```json
{{
  "volume_title": "卷标题",
  "target_word_count": 100000,
  "target_chapters": 50,
  "main_goal": "主要目标",
  "sub_goals": ["副线目标"],
  "character_growth": "人物成长",
  "external_conflict": "外部冲突",
  "internal_conflict": "内部冲突",
  "resolution": "解决方式",
  "opening_node": {{"chapter": 1, "description": "开卷节点"}},
  "quarter_node": {{"chapter": 13, "description": "1/4节点"}},
  "midpoint_node": {{"chapter": 25, "description": "中点节点"}},
  "three_quarter_node": {{"chapter": 38, "description": "3/4节点"}},
  "climax_node": {{"chapter": 50, "description": "卷末高潮"}},
  "new_suspense": ["新增悬念"],
  "resolved_suspense": ["解决的悬念"],
  "ongoing_suspense": ["推进的长线悬念"],
  "major_appeal_points": [
    {{"chapter": 10, "type": "装逼打脸", "description": "描述"}}
  ],
  "appeal_distribution": "小爽点分布描述",
  "new_characters": ["新角色"],
  "new_locations": ["新地点"],
  "new_settings": ["新设定"],
  "chapters": [
    {{
      "chapter_number": 1,
      "chapter_title": "章节标题",
      "target_word_count": 3000,
      "plot_goal": "剧情目标",
      "character_development": "人物发展",
      "info_reveal": "信息揭示",
      "scenes": [
        {{"scene_name": "场景名", "word_count": 1000, "pace": "fast"}}
      ],
      "conflict": "本章冲突",
      "appeal_point": "爽点设计",
      "suspense": "悬念/伏笔",
      "connect_previous": "承接上章",
      "lead_to_next": "引出下章",
      "core_goal": "本章核心目标，必须与plot_goal一致但更聚焦",
      "must_write": ["本章必须完成的剧情/人物/信息增量"],
      "allowed_progress": ["可轻微铺垫但不得完成的后续内容"],
      "must_not_write": ["本章禁止提前写入或揭晓的内容"],
      "reserved_for_future": {{"chapter_2": "保留给后续章节完成的核心事件"}},
      "stop_point": "本章必须停住的位置，不越过该剧情结果",
      "ending_hook": "章末追读钩子",
      "pace_type": "fast/medium/slow",
      "info_density": "high/medium/low"
    }}
  ]
}}
```
边界控制要求：
1. must_write 必须至少包含剧情目标、主要冲突或关键信息揭示之一。
2. allowed_progress 只能写线索和铺垫，不能写结果。
3. must_not_write 必须明确列出至少一个不能提前完成的后续事件或谜底。
4. reserved_for_future 必须用 chapter_N 映射后续章节要保留的核心事件。
5. stop_point 和 ending_hook 必须能指导章节结尾停在哪里、用什么牵引下一章。
"""
        return prompt

    async def _generate_rhythm_curve(self, volumes: List[VolumeOutlineSchema],
                                     skills_content: Dict[str, str]) -> str:
        """生成整体节奏曲线描述"""
        # 简化版：基于各卷的章节数量和节奏类型生成描述
        rhythm_desc = "整体节奏："

        for vol in volumes:
            fast_count = sum(1 for ch in vol.chapters if ch.pace_type == "fast")
            total = len(vol.chapters)
            if total > 0:
                fast_ratio = fast_count / total * 100
                rhythm_desc += f" 第{vol.volume_number}卷快节奏占{fast_ratio:.0f}%，"

        return rhythm_desc.rstrip("，")

    async def _generate_long_suspense(self, volumes: List[VolumeOutlineSchema],
                                      skills_content: Dict[str, str]) -> List[str]:
        """提取长线悬念"""
        long_suspense = set()

        for vol in volumes:
            long_suspense.update(vol.new_suspense)
            long_suspense.update(vol.ongoing_suspense)

        return list(long_suspense)

    async def _validate_outline(self, outline: BookOutlineSchema,
                                skills_content: Dict[str, str]) -> Dict[str, Any]:
        """验证大纲"""
        validation_result = {
            "valid": True,
            "warnings": [],
            "suggestions": []
        }

        # 检查基本完整性
        if outline.total_volumes == 0:
            validation_result["warnings"].append("没有分卷规划")
            validation_result["valid"] = False

        if outline.total_chapters == 0:
            validation_result["warnings"].append("没有章节规划")
            validation_result["valid"] = False

        if not outline.characters:
            validation_result["warnings"].append("缺少人物设定")

        # 检查字数合理性
        actual_word_count = sum(v.target_word_count for v in outline.volumes)
        if abs(actual_word_count - outline.target_word_count) > outline.target_word_count * 0.2:
            validation_result["warnings"].append(
                f"实际规划字数({actual_word_count})与目标({outline.target_word_count})偏差较大"
            )

        return validation_result

    async def _save_outline(self, outline: BookOutlineSchema) -> Path:
        """保存大纲"""
        outlines_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / outline.project_id /
            "novel" / "outline"
        )
        outlines_dir.mkdir(parents=True, exist_ok=True)

        outline_file = outlines_dir / f"{outline.book_id}_outline.json"

        # 转换为dict并保存
        outline_dict = outline.dict()
        with open(outline_file, 'w', encoding='utf-8') as f:
            json.dump(outline_dict, f, ensure_ascii=False, indent=2, default=str)

        legacy_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / outline.project_id /
            "outlines"
        )
        legacy_dir.mkdir(parents=True, exist_ok=True)
        with open(legacy_dir / outline_file.name, 'w', encoding='utf-8') as f:
            json.dump(outline_dict, f, ensure_ascii=False, indent=2, default=str)

        self.logger.info(f"Saved outline to {outline_file}")
        return outline_file

    async def _save_project_soul(self, outline: BookOutlineSchema) -> Path:
        """保存项目灵魂文档。"""
        soul_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / outline.project_id /
            "novel" / "soul"
        )
        soul_dir.mkdir(parents=True, exist_ok=True)
        soul_file = soul_dir / "project_soul.md"

        content = self._build_project_soul_markdown(outline)
        with open(soul_file, 'w', encoding='utf-8') as f:
            f.write(content)

        self.logger.info(f"Saved project soul to {soul_file}")
        return soul_file

    def _build_project_soul_markdown(self, outline: BookOutlineSchema) -> str:
        """根据大纲生成Project Soul Markdown。"""
        primary_character = outline.characters[0] if outline.characters else None
        average_volume_words = (
            outline.target_word_count // outline.total_volumes
            if outline.total_volumes else outline.target_word_count
        )
        average_chapter_words = (
            outline.target_word_count // outline.total_chapters
            if outline.total_chapters else 3000
        )

        canon_settings = [
            f"{setting.name}：{setting.description}"
            for setting in outline.world_settings
        ] or ["以当前大纲和后续终稿记忆为准，不得随意改写核心设定。"]

        character_lines = []
        for character in outline.characters:
            attributes = "；".join([
                f"{key}：{value}" for key, value in character.attributes.items()
            ])
            character_lines.append(
                f"- {character.name}（{character.role}）：{character.description}"
                + (f"；{attributes}" if attributes else "")
            )
        if not character_lines:
            character_lines.append("- 暂未生成主要人物；后续大纲更新时必须补齐。")

        suspense_lines = outline.long_term_suspense or ["旧主线悬念必须持续推进并最终回收。"]

        return f"""# 项目灵魂文档

## 一句话卖点
{outline.core_concept}

## 核心价值主张
1. 围绕“{outline.main_conflict or '核心冲突'}”持续制造目标、阻碍和选择。
2. 保持角色成长、信息揭示和读者期待同步推进。
3. 每个关键转折都要服务主线卖点，避免偏离《{outline.book_title}》的核心承诺。

## 目标读者画像
- 目标受众：{outline.target_audience or '以当前题材核心读者为主'}
- 阅读偏好：{outline.genre}、强目标、连续悬念、稳定追读
- 期待体验：清晰成长、明确冲突、章末牵引

## 类型定位
- 主类型：{outline.genre}
- 风格：节奏清晰，冲突前置，信息分批释放
- 主题：{outline.theme or '人物在压力中成长并完成主线目标'}

## 长度规划
- 目标字数：{outline.target_word_count}
- 卷数：{outline.total_volumes}
- 平均每卷：{average_volume_words}
- 章节数：约{outline.total_chapters}
- 平均每章：约{average_chapter_words}

## 世界观核心设定（Canon）

### 不可变设定
{self._numbered_lines(canon_settings)}

### 可变设定
1. 支线顺序可以微调，但不得破坏主线因果。
2. 配角戏份可以增减，但不得抢走主角成长和核心冲突。
3. 场景外壳可以替换，但不得改变已经确立的 Canon。

## 主角设定（Canon）

### 基本信息
{self._character_canon(primary_character)}

### 成长路线
{self._volume_growth_lines(outline)}

## 核心冲突
1. 主线冲突：{outline.main_conflict or '围绕核心目标持续升级'}
2. 外部冲突：{self._external_conflict_summary(outline)}
3. 内部冲突：角色必须在代价、风险和目标之间做选择。

## 长线悬念
{self._numbered_lines(suspense_lines)}

## 节奏原则
1. 每章必须有明确目标、阻碍或信息增量。
2. 章末需要保留下一章牵引，避免平铺直叙收尾。
3. 快节奏章节负责推进和爆发，慢节奏章节必须承担关系、设定或伏笔功能。
4. 不允许连续多章缺少冲突、爽点或重要信息。

## 禁止事项
1. 禁止违背已写入本文档的 Canon 设定。
2. 禁止主角无动机改变目标或性格。
3. 禁止无铺垫突破、反转或关键道具生效。
4. 禁止提前消耗后续章纲保留的重大谜底。
5. 禁止用长篇解释替代行动、冲突和选择。

## 风格要求
1. 文笔：简洁有力，避免重复解释。
2. 对话：体现人物身份、立场和当下压力。
3. 描写：优先服务动作、情绪和线索。
4. 节奏：每段都要推动场景、人物或悬念之一。

## 主要人物约束
{chr(10).join(character_lines)}

## 特殊约定
1. 每条长线伏笔必须记录并回收。
2. 任何新增设定必须同步进入记忆或 Canon。
3. 终稿前必须检查本章是否违背 Project Soul。
"""

    def _numbered_lines(self, values: List[str]) -> str:
        return "\n".join([f"{idx}. {value}" for idx, value in enumerate(values, 1)])

    def _character_canon(self, character: CharacterSchema | None) -> str:
        if not character:
            return "- 暂未生成主角设定，后续必须补齐。"

        attributes = "\n".join([
            f"- {key}：{value}" for key, value in character.attributes.items()
        ])
        return f"""- 姓名：{character.name}
- 角色：{character.role}
- 描述：{character.description}
{attributes if attributes else '- 补充属性：待后续细化'}"""

    def _volume_growth_lines(self, outline: BookOutlineSchema) -> str:
        if not outline.volumes:
            return "1. 大纲暂未生成分卷成长线，后续必须补齐。"

        return "\n".join([
            f"{volume.volume_number}. 第{volume.volume_number}卷《{volume.volume_title}》：{volume.character_growth}"
            for volume in outline.volumes
        ])

    def _external_conflict_summary(self, outline: BookOutlineSchema) -> str:
        conflicts = [
            volume.external_conflict
            for volume in outline.volumes
            if volume.external_conflict
        ]
        return "；".join(conflicts[:3]) if conflicts else "由分卷目标和敌对阻碍逐步升级。"
