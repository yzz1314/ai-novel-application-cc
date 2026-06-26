"""
记忆提取Agent
从章节正文中提取人物、世界观、剧情等关键信息
"""
import json
import uuid
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from schemas.memory_schemas import (
    CharacterMemory,
    WorldSettingMemory,
    PlotMemory,
    SuspenseMemory,
    TimelineEvent,
    MemoryExtractionRequest,
    MemoryExtractionResponse
)
from schemas.chapter_schemas import ChapterContent
from llm.client import LLMClient
from config import settings
from utils.logger import get_logger


class MemoryExtractorAgent(BaseAgent):
    """记忆提取Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("MemoryExtractorAgent")
        self.supported_tasks = ["memory_extraction"]
        self.llm_client = llm_client or LLMClient()
        self.logger = get_logger("MemoryExtractorAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行记忆提取任务

        流程：
        1. 加载章节内容
        2. 提取各类记忆
        3. 更新或创建记忆
        4. 保存记忆
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)

            # 2. 解析提取请求
            extract_request = MemoryExtractionRequest(**request.parameters)
            self.logger.info(f"Extracting memory from chapter: {extract_request.chapter_id}")

            # 3. 加载章节内容
            chapter_content = await self._load_chapter(extract_request)

            # 4. 加载已有记忆
            existing_memories = await self._load_existing_memories(extract_request)

            # 5. 提取新记忆
            extracted_memories = await self._extract_memories(
                extract_request, chapter_content, existing_memories
            )

            # 6. 保存记忆
            output_refs = await self._save_memories(extract_request, extracted_memories, chapter_content)

            # 7. 构建响应
            structured_output = {
                "chapter_id": extract_request.chapter_id,
                "total_extracted": extracted_memories["total_extracted"],
                "new_created": extracted_memories["new_created"],
                "updated": extracted_memories["updated"],
                "characters_count": len(extracted_memories["characters"]),
                "settings_count": len(extracted_memories["world_settings"]),
                "plots_count": len(extracted_memories["plots"]),
                "suspenses_count": len(extracted_memories["suspenses"]),
                "events_count": len(extracted_memories["timeline_events"]),
                "memory_paths": output_refs,
            }

            return self._build_response(
                request=request,
                status="success",
                output_refs=output_refs,
                structured_output=structured_output
            )

        except Exception as e:
            self.logger.error(f"Memory extraction failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "MEMORY_EXTRACTION_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    async def _load_chapter(self, extract_request: MemoryExtractionRequest) -> ChapterContent:
        """加载章节内容"""
        # 简化版：假设chapter_id包含了路径信息
        # 实际应该从数据库或文件系统中查询
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / extract_request.project_id
        chapter_dirs = [
            project_root / "novel" / "chapters" / "final" / extract_request.book_id,
            project_root / "novel" / "chapters" / "drafts" / extract_request.book_id,
            project_root / "books" / extract_request.book_id / "chapters",
            project_root / "books" / extract_request.book_id,
        ]

        # 遍历查找章节文件
        for chapters_dir in chapter_dirs:
            if not chapters_dir.exists():
                continue
            for chapter_file in chapters_dir.rglob("*.json"):
                with open(chapter_file, 'r', encoding='utf-8') as f:
                    chapter_data = json.load(f)
                    if (
                        chapter_data.get("chapter_id") == extract_request.chapter_id
                        or chapter_data.get("chapterId") == extract_request.chapter_id
                        or chapter_file.stem == extract_request.chapter_id
                    ):
                        return ChapterContent(**self._normalize_chapter_data(chapter_data))

        raise FileNotFoundError(f"Chapter not found: {extract_request.chapter_id}")

    def _normalize_chapter_data(self, chapter_data: Dict[str, Any]) -> Dict[str, Any]:
        """兼容 Java/前端读取时使用的 camelCase 与 Python schema 的 snake_case。"""
        mapping = {
            "chapterId": "chapter_id",
            "bookId": "book_id",
            "volumeNumber": "volume_number",
            "chapterNumber": "chapter_number",
            "chapterTitle": "chapter_title",
            "wordCount": "word_count",
            "basedOnOutline": "based_on_outline",
            "outlineSummary": "outline_summary",
            "usedSkills": "used_skills",
            "referencedChapters": "referenced_chapters",
            "qualityScore": "quality_score",
            "reviewStatus": "review_status",
            "reviewComments": "review_comments",
            "createdAt": "created_at",
            "updatedAt": "updated_at",
            "createdBy": "created_by",
        }
        normalized = dict(chapter_data)
        for source, target in mapping.items():
            if source in normalized and target not in normalized:
                normalized[target] = normalized[source]
        return normalized

    async def _project_memory_dir(self, project_id: str) -> Path:
        memory_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "memory"
        memory_dir.mkdir(parents=True, exist_ok=True)
        (memory_dir / "snapshots").mkdir(parents=True, exist_ok=True)
        return memory_dir

    async def _book_memory_dir(self, extract_request: MemoryExtractionRequest) -> Path:
        memory_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / extract_request.project_id /
            "books" / extract_request.book_id / "memories"
        )
        memory_dir.mkdir(parents=True, exist_ok=True)
        return memory_dir

    async def _load_json_list(self, file_path: Path) -> List[Dict[str, Any]]:
        if not file_path.exists():
            return []
        with open(file_path, 'r', encoding='utf-8') as f:
            return json.load(f)

    async def _write_json(self, file_path: Path, data: Any):
        file_path.parent.mkdir(parents=True, exist_ok=True)
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2, default=str)

    async def _write_markdown_memories(
        self,
        memory_dir: Path,
        json_memories: Dict[str, List[Dict[str, Any]]],
        chapter_content: ChapterContent,
        extracted_memories: Dict,
    ) -> List[str]:
        """同步文档要求的6类Markdown记忆文件。"""
        paths = []

        characters = json_memories.get("characters", [])
        character_lines = ["# 人物状态记忆", ""]
        for char in characters:
            character_lines.extend([
                f"## {char.get('name', '未命名人物')}",
                f"- ID: {char.get('character_id', '')}",
                f"- 角色类型: {char.get('role', '')}",
                f"- 描述: {char.get('description', '')}",
                f"- 当前状态: {json.dumps(char.get('current_status', {}), ensure_ascii=False)}",
                f"- 出场章节: {', '.join(map(str, char.get('appearances', [])))}",
                f"- 最后更新: 第{char.get('last_updated', '')}章",
                "",
            ])
        paths.append(await self._write_markdown(memory_dir / "characters.md", character_lines))

        suspenses = json_memories.get("suspenses", [])
        foreshadowing_lines = ["# 伏笔记忆", ""]
        for suspense in suspenses:
            foreshadowing_lines.extend([
                f"## {suspense.get('title', '未命名伏笔')}",
                f"- ID: {suspense.get('suspense_id', '')}",
                f"- 类型: {suspense.get('suspense_type', '')}",
                f"- 状态: {suspense.get('status', '')}",
                f"- 设置章节: 第{suspense.get('set_chapter', '')}章",
                f"- 问题: {suspense.get('question', '')}",
                f"- 描述: {suspense.get('description', '')}",
                "",
            ])
        paths.append(await self._write_markdown(memory_dir / "foreshadowing.md", foreshadowing_lines))

        timeline = json_memories.get("timeline_events", [])
        timeline_lines = ["# 时间线记忆", ""]
        for event in timeline:
            timeline_lines.extend([
                f"## 第{event.get('chapter', '')}章：{event.get('title', '未命名事件')}",
                f"- ID: {event.get('event_id', '')}",
                f"- 类型: {event.get('event_type', '')}",
                f"- 描述: {event.get('description', '')}",
                f"- 涉及人物: {', '.join(event.get('involved_characters', []))}",
                f"- 后果: {'；'.join(event.get('consequences', []))}",
                "",
            ])
        paths.append(await self._write_markdown(memory_dir / "timeline.md", timeline_lines))

        relationship_lines = ["# 人物关系记忆", ""]
        for char in characters:
            relationships = char.get("relationships", {})
            if not relationships:
                continue
            relationship_lines.append(f"## {char.get('name', '')}")
            for target, relation in relationships.items():
                relationship_lines.append(f"- {target}: {relation}")
            relationship_lines.append("")
        paths.append(await self._write_markdown(memory_dir / "relationships.md", relationship_lines))

        cognition_lines = ["# 角色认知记忆", ""]
        for char in characters:
            cognition_lines.extend([
                f"## {char.get('name', '未命名人物')}",
                f"- 已知状态: {json.dumps(char.get('current_status', {}), ensure_ascii=False)}",
                f"- 重要经历: {json.dumps(char.get('important_events', []), ensure_ascii=False)}",
                "",
            ])
        paths.append(await self._write_markdown(memory_dir / "cognition.md", cognition_lines))

        settings = json_memories.get("world_settings", [])
        plots = json_memories.get("plots", [])
        canon_lines = ["# Canon正史规则", ""]
        canon_lines.extend([
            f"## 最近摄取章节",
            f"- 章节: 第{chapter_content.volume_number}卷 第{chapter_content.chapter_number}章",
            f"- 标题: {chapter_content.chapter_title}",
            f"- 摄取时间: {datetime.now().isoformat()}",
            "",
        ])
        for setting in settings:
            canon_lines.extend([
                f"## {setting.get('name', '未命名设定')}",
                f"- 类别: {setting.get('category', '')}",
                f"- 描述: {setting.get('description', '')}",
                f"- 属性: {json.dumps(setting.get('properties', {}), ensure_ascii=False)}",
                "",
            ])
        for plot in plots:
            canon_lines.extend([
                f"## 剧情：{plot.get('title', '未命名剧情')}",
                f"- 状态: {plot.get('status', '')}",
                f"- 描述: {plot.get('description', '')}",
                "",
            ])
        paths.append(await self._write_markdown(memory_dir / "canon.md", canon_lines))

        snapshot = {
            "chapter_id": chapter_content.chapter_id,
            "book_id": chapter_content.book_id,
            "volume_number": chapter_content.volume_number,
            "chapter_number": chapter_content.chapter_number,
            "chapter_title": chapter_content.chapter_title,
            "extracted_at": datetime.now().isoformat(),
            "characters": [memory.dict() for memory in extracted_memories.get("characters", [])],
            "world_settings": [memory.dict() for memory in extracted_memories.get("world_settings", [])],
            "plots": [memory.dict() for memory in extracted_memories.get("plots", [])],
            "suspenses": [memory.dict() for memory in extracted_memories.get("suspenses", [])],
            "timeline_events": [memory.dict() for memory in extracted_memories.get("timeline_events", [])],
        }
        snapshot_file = (
            memory_dir / "snapshots" /
            f"{chapter_content.book_id}_v{chapter_content.volume_number}_c{chapter_content.chapter_number}.json"
        )
        await self._write_json(snapshot_file, snapshot)
        paths.append(str(snapshot_file))

        return paths

    async def _write_markdown(self, file_path: Path, lines: List[str]) -> str:
        file_path.parent.mkdir(parents=True, exist_ok=True)
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write("\n".join(lines).rstrip() + "\n")
        return str(file_path)

    async def _load_existing_memories(self, extract_request: MemoryExtractionRequest) -> Dict:
        """加载已有记忆"""
        memory_dir = await self._book_memory_dir(extract_request)

        existing = {
            "characters": {},
            "world_settings": {},
            "plots": {},
            "suspenses": {},
            "timeline_events": []
        }

        if not memory_dir.exists():
            return existing

        # 加载各类记忆
        for memory_type in ["characters", "world_settings", "plots", "suspenses"]:
            memory_file = memory_dir / f"{memory_type}.json"
            if memory_file.exists():
                with open(memory_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    existing[memory_type] = {item["name"]: item for item in data}

        # 加载时间线
        timeline_file = memory_dir / "timeline.json"
        if timeline_file.exists():
            with open(timeline_file, 'r', encoding='utf-8') as f:
                existing["timeline_events"] = json.load(f)

        return existing

    async def _extract_memories(self, extract_request: MemoryExtractionRequest,
                                chapter_content: ChapterContent,
                                existing_memories: Dict) -> Dict:
        """提取记忆"""
        self.logger.info("Extracting memories from chapter content...")

        # 构建提取prompt
        prompt = self._build_extraction_prompt(
            extract_request, chapter_content, existing_memories
        )

        # 调用LLM提取
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
        extracted_data = json.loads(llm_response["content"])

        # 处理提取的数据
        result = {
            "characters": [],
            "world_settings": [],
            "plots": [],
            "suspenses": [],
            "timeline_events": [],
            "total_extracted": 0,
            "new_created": 0,
            "updated": 0
        }

        # 处理人物记忆
        if extract_request.extract_characters:
            for char_data in extracted_data.get("characters", []):
                char_memory = await self._process_character_memory(
                    char_data, chapter_content, existing_memories["characters"]
                )
                result["characters"].append(char_memory)
                result["total_extracted"] += 1
                if char_data["name"] not in existing_memories["characters"]:
                    result["new_created"] += 1
                else:
                    result["updated"] += 1

        # 处理世界观记忆
        if extract_request.extract_world_settings:
            for setting_data in extracted_data.get("world_settings", []):
                setting_memory = await self._process_setting_memory(
                    setting_data, chapter_content, existing_memories["world_settings"]
                )
                result["world_settings"].append(setting_memory)
                result["total_extracted"] += 1
                if setting_data["name"] not in existing_memories["world_settings"]:
                    result["new_created"] += 1
                else:
                    result["updated"] += 1

        # 处理剧情记忆
        if extract_request.extract_plot:
            for plot_data in extracted_data.get("plots", []):
                plot_memory = await self._process_plot_memory(
                    plot_data, chapter_content, existing_memories["plots"]
                )
                result["plots"].append(plot_memory)
                result["total_extracted"] += 1

        # 处理悬念记忆
        if extract_request.extract_suspense:
            for suspense_data in extracted_data.get("suspenses", []):
                suspense_memory = await self._process_suspense_memory(
                    suspense_data, chapter_content, existing_memories["suspenses"]
                )
                result["suspenses"].append(suspense_memory)
                result["total_extracted"] += 1

        # 处理时间线事件
        if extract_request.extract_timeline:
            for event_data in extracted_data.get("timeline_events", []):
                event = TimelineEvent(
                    event_id=f"event_{uuid.uuid4().hex[:8]}",
                    chapter=chapter_content.chapter_number,
                    event_type=event_data.get("event_type", "event"),
                    title=event_data.get("title", ""),
                    description=event_data.get("description", ""),
                    involved_characters=event_data.get("involved_characters", []),
                    involved_settings=event_data.get("involved_settings", []),
                    consequences=event_data.get("consequences", [])
                )
                result["timeline_events"].append(event)
                result["total_extracted"] += 1

        return result

    def _build_extraction_prompt(self, extract_request: MemoryExtractionRequest,
                                 chapter_content: ChapterContent,
                                 existing_memories: Dict) -> str:
        """构建提取prompt"""

        # 已有人物列表
        existing_chars = list(existing_memories["characters"].keys())[:10]
        existing_chars_str = "、".join(existing_chars) if existing_chars else "无"

        prompt = f"""
你是一位专业的小说内容分析师。请从以下章节中提取关键信息。

## 章节信息

- 标题：{chapter_content.chapter_title}
- 章节号：第{chapter_content.volume_number}卷 第{chapter_content.chapter_number}章
- 字数：{chapter_content.word_count}字

## 章节正文（前1000字）

{chapter_content.content[:1000]}...

## 已有人物

{existing_chars_str}

## 提取要求

请提取以下信息：

### 1. 人物信息
- 出场人物（包括新人物和已有人物）
- 人物当前状态（境界、位置、情绪等）
- 人物关系变化
- 重要事件

### 2. 世界观设定
- 提及的地点
- 提及的组织/势力
- 提及的规则/设定
- 提及的物品/技能

### 3. 剧情信息
- 本章推进的剧情线
- 剧情关键事件

### 4. 悬念信息
- 新增的悬念
- 解决的悬念

### 5. 时间线事件
- 本章发生的重要事件

## 输出格式

请以JSON格式返回：

```json
{{
  "characters": [
    {{
      "name": "人物名",
      "role": "protagonist/supporting/antagonist",
      "description": "人物描述（如果是新人物）",
      "current_status": {{"境界": "筑基期", "位置": "天剑宗"}},
      "status_changes": [{{"from": "练气期", "to": "筑基期", "event": "突破"}}],
      "relationships": {{"李云": "师兄"}},
      "important_events": [{{"event": "与主角切磋", "impact": "关系加深"}}]
    }}
  ],
  "world_settings": [
    {{
      "name": "设定名",
      "category": "location/organization/rule/item/skill",
      "description": "描述",
      "properties": {{"等级": "一流宗门"}},
      "status_changes": [{{"change": "被攻击", "impact": "危机"}}]
    }}
  ],
  "plots": [
    {{
      "plot_type": "main/sub",
      "title": "剧情标题",
      "description": "剧情描述",
      "involved_characters": ["人物名"],
      "key_events": [{{"event": "事件", "consequence": "后果"}}],
      "status": "ongoing/completed"
    }}
  ],
  "suspenses": [
    {{
      "suspense_type": "long/medium/short",
      "title": "悬念标题",
      "description": "悬念描述",
      "question": "悬念问题",
      "status": "active/resolved"
    }}
  ],
  "timeline_events": [
    {{
      "event_type": "battle/breakthrough/meeting/conflict",
      "title": "事件标题",
      "description": "事件描述",
      "involved_characters": ["人物名"],
      "consequences": ["后果1", "后果2"]
    }}
  ]
}}
```

注意：
1. 只提取明确出现在正文中的信息
2. 如果是已有人物，只更新变化的部分
3. 描述要简洁准确
"""
        return prompt

    async def _process_character_memory(self, char_data: Dict, chapter_content: ChapterContent,
                                       existing_chars: Dict) -> CharacterMemory:
        """处理人物记忆"""
        name = char_data["name"]

        if name in existing_chars:
            # 更新已有人物
            existing = CharacterMemory(**existing_chars[name])
            # 更新状态
            if char_data.get("current_status"):
                existing.current_status.update(char_data["current_status"])
            # 添加状态变化
            if char_data.get("status_changes"):
                for change in char_data["status_changes"]:
                    change["chapter"] = chapter_content.chapter_number
                    existing.status_history.append(change)
            # 更新关系
            if char_data.get("relationships"):
                existing.relationships.update(char_data["relationships"])
            # 添加事件
            if char_data.get("important_events"):
                for event in char_data["important_events"]:
                    event["chapter"] = chapter_content.chapter_number
                    existing.important_events.append(event)
            # 记录出场
            if chapter_content.chapter_number not in existing.appearances:
                existing.appearances.append(chapter_content.chapter_number)
            existing.last_appearance = chapter_content.chapter_number
            existing.last_updated = chapter_content.chapter_number
            existing.updated_at = datetime.now()

            return existing
        else:
            # 创建新人物
            return CharacterMemory(
                character_id=f"char_{uuid.uuid4().hex[:8]}",
                name=name,
                role=char_data.get("role", "supporting"),
                description=char_data.get("description", ""),
                current_status=char_data.get("current_status", {}),
                relationships=char_data.get("relationships", {}),
                appearances=[chapter_content.chapter_number],
                last_appearance=chapter_content.chapter_number,
                first_mentioned=chapter_content.chapter_number,
                last_updated=chapter_content.chapter_number
            )

    async def _process_setting_memory(self, setting_data: Dict, chapter_content: ChapterContent,
                                     existing_settings: Dict) -> WorldSettingMemory:
        """处理世界观记忆"""
        name = setting_data["name"]

        if name in existing_settings:
            # 更新已有设定
            existing = WorldSettingMemory(**existing_settings[name])
            # 添加状态变化
            if setting_data.get("status_changes"):
                for change in setting_data["status_changes"]:
                    change["chapter"] = chapter_content.chapter_number
                    existing.status_changes.append(change)
            # 记录提及
            if chapter_content.chapter_number not in existing.mentions:
                existing.mentions.append(chapter_content.chapter_number)
            existing.last_updated = chapter_content.chapter_number
            existing.updated_at = datetime.now()

            return existing
        else:
            # 创建新设定
            return WorldSettingMemory(
                setting_id=f"setting_{uuid.uuid4().hex[:8]}",
                name=name,
                category=setting_data.get("category", "location"),
                description=setting_data.get("description", ""),
                properties=setting_data.get("properties", {}),
                mentions=[chapter_content.chapter_number],
                first_mentioned=chapter_content.chapter_number,
                last_updated=chapter_content.chapter_number
            )

    async def _process_plot_memory(self, plot_data: Dict, chapter_content: ChapterContent,
                                  existing_plots: Dict) -> PlotMemory:
        """处理剧情记忆"""
        # 简化版：直接创建新剧情或更新
        title = plot_data.get("title", "")

        return PlotMemory(
            plot_id=f"plot_{uuid.uuid4().hex[:8]}",
            plot_type=plot_data.get("plot_type", "sub"),
            title=title,
            description=plot_data.get("description", ""),
            start_chapter=chapter_content.chapter_number,
            involved_characters=plot_data.get("involved_characters", []),
            key_events=plot_data.get("key_events", []),
            status=plot_data.get("status", "ongoing")
        )

    async def _process_suspense_memory(self, suspense_data: Dict, chapter_content: ChapterContent,
                                      existing_suspenses: Dict) -> SuspenseMemory:
        """处理悬念记忆"""
        return SuspenseMemory(
            suspense_id=f"suspense_{uuid.uuid4().hex[:8]}",
            suspense_type=suspense_data.get("suspense_type", "short"),
            title=suspense_data.get("title", ""),
            description=suspense_data.get("description", ""),
            question=suspense_data.get("question", ""),
            set_chapter=chapter_content.chapter_number,
            status=suspense_data.get("status", "active")
        )

    async def _save_memories(self, extract_request: MemoryExtractionRequest,
                            extracted_memories: Dict, chapter_content: ChapterContent) -> List[str]:
        """保存记忆"""
        memory_dir = await self._book_memory_dir(extract_request)
        project_memory_dir = await self._project_memory_dir(extract_request.project_id)
        output_refs = []

        # 保存各类记忆
        for memory_type in ["characters", "world_settings", "plots", "suspenses"]:
            if extracted_memories[memory_type]:
                memory_file = memory_dir / f"{memory_type}.json"

                # 加载已有数据
                existing_data = []
                if memory_file.exists():
                    with open(memory_file, 'r', encoding='utf-8') as f:
                        existing_data = json.load(f)

                # 合并新数据
                existing_dict = {item.get("name") or item.get("title") or item.get("suspense_id"): item
                                for item in existing_data}

                for memory in extracted_memories[memory_type]:
                    key = memory.name if hasattr(memory, 'name') else memory.title
                    existing_dict[key] = memory.dict()

                # 保存
                merged = list(existing_dict.values())
                await self._write_json(memory_file, merged)
                await self._write_json(project_memory_dir / f"{memory_type}.json", merged)
                output_refs.extend([str(memory_file), str(project_memory_dir / f"{memory_type}.json")])

        # 保存时间线
        if extracted_memories["timeline_events"]:
            timeline_file = memory_dir / "timeline.json"

            existing_timeline = []
            if timeline_file.exists():
                with open(timeline_file, 'r', encoding='utf-8') as f:
                    existing_timeline = json.load(f)

            for event in extracted_memories["timeline_events"]:
                existing_timeline.append(event.dict())

            # 按章节排序
            existing_timeline.sort(key=lambda x: x["chapter"])

            await self._write_json(timeline_file, existing_timeline)
            await self._write_json(project_memory_dir / "timeline.json", existing_timeline)
            output_refs.extend([str(timeline_file), str(project_memory_dir / "timeline.json")])

        json_memories = {
            "characters": await self._load_json_list(project_memory_dir / "characters.json"),
            "world_settings": await self._load_json_list(project_memory_dir / "world_settings.json"),
            "plots": await self._load_json_list(project_memory_dir / "plots.json"),
            "suspenses": await self._load_json_list(project_memory_dir / "suspenses.json"),
            "timeline_events": await self._load_json_list(project_memory_dir / "timeline.json"),
        }
        output_refs.extend(
            await self._write_markdown_memories(
                project_memory_dir,
                json_memories,
                chapter_content,
                extracted_memories,
            )
        )

        self.logger.info(f"Saved memories to {memory_dir}")
        return output_refs
