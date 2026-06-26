"""
记忆查询Agent
根据创作需求查询相关记忆并检查连续性
"""
import json
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List, Optional

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from schemas.memory_schemas import (
    MemoryQueryRequest,
    MemoryQueryResponse,
    ContinuityCheckRequest,
    ContinuityCheckResponse,
    ContinuityIssue
)
from llm.client import LLMClient
from config import settings
from utils.logger import get_logger


class MemoryQueryAgent(BaseAgent):
    """记忆查询Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("MemoryQueryAgent")
        self.supported_tasks = ["memory_query", "continuity_check"]
        self.llm_client = llm_client or LLMClient()
        self.logger = get_logger("MemoryQueryAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """执行记忆查询或连续性检查任务"""
        self.metrics["start_time"] = datetime.now()

        try:
            await self.validate_request(request)

            # 判断任务类型
            if "query_type" in request.parameters:
                # 记忆查询
                return await self._handle_query(request)
            else:
                # 连续性检查
                return await self._handle_continuity_check(request)

        except Exception as e:
            self.logger.error(f"Memory query failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "MEMORY_QUERY_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    async def _handle_query(self, request: AgentRequest) -> AgentResponse:
        """处理记忆查询"""
        query_request = MemoryQueryRequest(**request.parameters)
        self.logger.info(f"Querying {query_request.query_type}: {query_request.query}")

        # 加载记忆数据
        memories = await self._load_memories(query_request)

        # 执行查询
        results = await self._query_memories(query_request, memories)

        structured_output = {
            "query_type": query_request.query_type,
            "total_found": len(results),
            "returned": min(len(results), query_request.limit),
            "results": results[:query_request.limit]
        }

        return self._build_response(
            request=request,
            status="success",
            structured_output=structured_output
        )

    async def _handle_continuity_check(self, request: AgentRequest) -> AgentResponse:
        """处理连续性检查"""
        check_request = ContinuityCheckRequest(**request.parameters)
        self.logger.info(f"Checking continuity for chapter: {check_request.chapter_id}")

        chapter = await self._load_chapter_for_check(check_request)
        memories = await self._load_all_memories(check_request.project_id, check_request.book_id)
        issues: List[Dict[str, Any]] = []

        chapter_number = self._chapter_number(chapter, check_request)
        content = str(chapter.get("content") or chapter.get("chapter_content") or "")

        if check_request.check_characters:
            issues.extend(self._check_character_continuity(content, chapter_number, memories))
        if check_request.check_settings:
            issues.extend(self._check_setting_continuity(content, chapter_number, memories))
        if check_request.check_timeline:
            issues.extend(self._check_timeline_continuity(content, chapter_number, memories))

        counts = self._count_by_severity(issues)
        report_path = await self._save_continuity_report(
            check_request=check_request,
            chapter=chapter,
            issues=issues,
            counts=counts
        )

        structured_output = {
            "chapter_id": check_request.chapter_id,
            "book_id": check_request.book_id,
            "chapter_number": chapter_number,
            "chapter_title": chapter.get("chapter_title") or chapter.get("chapterTitle") or "",
            "has_issues": len(issues) > 0,
            "issues": issues,
            "critical_count": counts["critical"],
            "major_count": counts["major"],
            "minor_count": counts["minor"],
            "report_path": str(report_path)
        }

        return self._build_response(
            request=request,
            status="success",
            output_refs=[str(report_path)],
            structured_output=structured_output
        )

    async def _load_chapter_for_check(self, check_request: ContinuityCheckRequest) -> Dict[str, Any]:
        """从常见章节产物路径加载待检查章节。"""
        if check_request.chapter_content:
            return self._normalize_chapter({
                "chapter_id": check_request.chapter_id,
                "book_id": check_request.book_id,
                "volume_number": check_request.volume_number,
                "chapter_number": check_request.chapter_number,
                "chapter_title": check_request.chapter_title or check_request.chapter_id,
                "content": check_request.chapter_content,
            })

        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / check_request.project_id
        chapter_number = check_request.chapter_number
        file_candidates: List[Path] = []

        if chapter_number is not None:
            for stage in ["final", "drafts"]:
                file_candidates.append(
                    project_root / "novel" / "chapters" / stage /
                    check_request.book_id / f"volume_{check_request.volume_number or 1}" /
                    f"chapter_{chapter_number}.json"
                )
            file_candidates.append(
                project_root / "books" / check_request.book_id /
                "chapters" / f"chapter_{chapter_number}.json"
            )

        search_roots = [
            project_root / "novel" / "chapters" / "final" / check_request.book_id,
            project_root / "novel" / "chapters" / "drafts" / check_request.book_id,
            project_root / "books" / check_request.book_id / "chapters",
            project_root / "books" / check_request.book_id,
        ]

        for candidate in file_candidates:
            loaded = self._try_load_matching_chapter(candidate, check_request)
            if loaded:
                return loaded

        for root in search_roots:
            if not root.exists():
                continue
            for file_path in root.rglob("*.json"):
                loaded = self._try_load_matching_chapter(file_path, check_request)
                if loaded:
                    return loaded

        raise FileNotFoundError(f"Chapter not found for continuity check: {check_request.chapter_id}")

    def _try_load_matching_chapter(
        self,
        file_path: Path,
        check_request: ContinuityCheckRequest
    ) -> Optional[Dict[str, Any]]:
        if not file_path.exists() or not file_path.is_file():
            return None
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except Exception:
            return None

        normalized = self._normalize_chapter(data)
        expected_chapter_id = check_request.chapter_id
        expected_chapter_number = check_request.chapter_number
        file_stem = file_path.stem

        if (
            normalized.get("chapter_id") == expected_chapter_id
            or normalized.get("chapterId") == expected_chapter_id
            or file_stem == expected_chapter_id
        ):
            return normalized

        if expected_chapter_number is not None:
            normalized_number = self._to_int(normalized.get("chapter_number"))
            if normalized_number == expected_chapter_number:
                return normalized

        return None

    def _normalize_chapter(self, chapter: Dict[str, Any]) -> Dict[str, Any]:
        mapping = {
            "chapterId": "chapter_id",
            "bookId": "book_id",
            "volumeNumber": "volume_number",
            "chapterNumber": "chapter_number",
            "chapterTitle": "chapter_title",
            "wordCount": "word_count",
        }
        normalized = dict(chapter)
        for source, target in mapping.items():
            if source in normalized and target not in normalized:
                normalized[target] = normalized[source]
        return normalized

    async def _load_all_memories(self, project_id: str, book_id: str) -> Dict[str, List[Dict[str, Any]]]:
        project_memory_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "memory"
        book_memory_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "books" / book_id / "memories"
        )

        memory_files = {
            "characters": "characters.json",
            "world_settings": "world_settings.json",
            "plots": "plots.json",
            "suspenses": "suspenses.json",
            "timeline": "timeline.json",
        }
        memories: Dict[str, List[Dict[str, Any]]] = {}
        for key, file_name in memory_files.items():
            file_path = project_memory_dir / file_name
            if not file_path.exists():
                file_path = book_memory_dir / file_name
            memories[key] = self._read_json_list(file_path)
        return memories

    def _read_json_list(self, file_path: Path) -> List[Dict[str, Any]]:
        if not file_path.exists():
            return []
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except Exception:
            return []

    def _check_character_continuity(
        self,
        content: str,
        chapter_number: Optional[int],
        memories: Dict[str, List[Dict[str, Any]]]
    ) -> List[Dict[str, Any]]:
        issues: List[Dict[str, Any]] = []
        if chapter_number is None:
            issues.append(self._issue(
                "character",
                "minor",
                "章节号缺失",
                "无法根据章节号检查人物出场顺序。",
                [],
                "补充 chapter_number 后重新检查。",
            ))
            return issues

        for character in memories.get("characters", []):
            names = [character.get("name", ""), *character.get("aliases", [])]
            names = [name for name in names if isinstance(name, str) and name]
            mentioned = any(name in content for name in names)
            if not mentioned:
                continue

            first_mentioned = self._to_int(character.get("first_mentioned"))
            appearances = [self._to_int(value) for value in character.get("appearances", [])]
            appearances = [value for value in appearances if value is not None]

            if first_mentioned is not None and first_mentioned > chapter_number:
                issues.append(self._issue(
                    "character",
                    "major",
                    "人物提前出场",
                    f"{character.get('name')} 的记忆首次提及在第 {first_mentioned} 章，但本章已经出现。",
                    [chapter_number, first_mentioned],
                    "确认人物是否应提前出场；如是，请更新人物记忆的首次提及章节。",
                ))

            if appearances and chapter_number not in appearances:
                issues.append(self._issue(
                    "character",
                    "minor",
                    "人物出场记录未更新",
                    f"{character.get('name')} 在本章文本中出现，但人物记忆 appearances 未包含第 {chapter_number} 章。",
                    [chapter_number],
                    "终稿摄取后更新人物出场记录，或确认本次检查使用的是最新记忆。",
                ))

            future_events = self._future_records(character.get("important_events", []), chapter_number)
            future_events.extend(self._future_records(character.get("status_history", []), chapter_number))
            for record in future_events:
                event_text = self._record_text(record)
                if event_text and event_text in content:
                    issues.append(self._issue(
                        "character",
                        "major",
                        "人物未来状态提前出现",
                        f"{character.get('name')} 的未来事件/状态疑似在本章提前出现：{event_text}",
                        [chapter_number, self._to_int(record.get("chapter"))],
                        "把该状态变化移回规划章节，或调整记忆中的章节归属。",
                    ))

        return issues

    def _check_setting_continuity(
        self,
        content: str,
        chapter_number: Optional[int],
        memories: Dict[str, List[Dict[str, Any]]]
    ) -> List[Dict[str, Any]]:
        issues: List[Dict[str, Any]] = []
        if chapter_number is None:
            return issues

        for setting in memories.get("world_settings", []):
            name = setting.get("name", "")
            if not isinstance(name, str) or not name or name not in content:
                continue

            first_mentioned = self._to_int(setting.get("first_mentioned"))
            if first_mentioned is not None and first_mentioned > chapter_number:
                issues.append(self._issue(
                    "setting",
                    "major",
                    "设定提前揭示",
                    f"{name} 的记忆首次提及在第 {first_mentioned} 章，但本章已经出现。",
                    [chapter_number, first_mentioned],
                    "确认该设定是否可提前出现；若不可，请从本章删除或改为暗示。",
                ))

            for record in self._future_records(setting.get("status_changes", []), chapter_number):
                change_text = self._record_text(record)
                if change_text and change_text in content:
                    issues.append(self._issue(
                        "setting",
                        "major",
                        "设定未来变化提前出现",
                        f"{name} 的未来变化疑似在本章提前出现：{change_text}",
                        [chapter_number, self._to_int(record.get("chapter"))],
                        "保留当前设定状态，把未来变化移到对应章节。",
                    ))

        return issues

    def _check_timeline_continuity(
        self,
        content: str,
        chapter_number: Optional[int],
        memories: Dict[str, List[Dict[str, Any]]]
    ) -> List[Dict[str, Any]]:
        issues: List[Dict[str, Any]] = []
        if chapter_number is None:
            return issues

        seen_ids = set()
        for event in memories.get("timeline", []):
            event_id = event.get("event_id")
            if event_id:
                if event_id in seen_ids:
                    issues.append(self._issue(
                        "timeline",
                        "minor",
                        "时间线事件重复",
                        f"时间线中存在重复事件 ID：{event_id}",
                        [self._to_int(event.get("chapter"))],
                        "合并重复事件，保留权威时间线记录。",
                    ))
                seen_ids.add(event_id)

            event_chapter = self._to_int(event.get("chapter"))
            if event_chapter is None or event_chapter <= chapter_number:
                continue

            title = str(event.get("title") or "")
            description = str(event.get("description") or "")
            if (title and title in content) or self._long_phrase_in_content(description, content):
                issues.append(self._issue(
                    "timeline",
                    "critical",
                    "未来时间线事件提前发生",
                    f"时间线第 {event_chapter} 章事件疑似在第 {chapter_number} 章提前出现：{title or description[:30]}",
                    [chapter_number, event_chapter],
                    "删除或弱化该事件，只保留允许的伏笔信息。",
                ))

        return issues

    def _future_records(self, records: Any, chapter_number: int) -> List[Dict[str, Any]]:
        if not isinstance(records, list):
            return []
        result = []
        for record in records:
            if not isinstance(record, dict):
                continue
            record_chapter = self._to_int(record.get("chapter"))
            if record_chapter is not None and record_chapter > chapter_number:
                result.append(record)
        return result

    def _record_text(self, record: Dict[str, Any]) -> str:
        for key in ["event", "change", "description", "impact", "to"]:
            value = record.get(key)
            if isinstance(value, str) and len(value.strip()) >= 4:
                return value.strip()
        return ""

    def _long_phrase_in_content(self, phrase: str, content: str) -> bool:
        phrase = phrase.strip()
        if len(phrase) < 8:
            return False
        return phrase in content or phrase[:20] in content

    def _chapter_number(
        self,
        chapter: Dict[str, Any],
        check_request: ContinuityCheckRequest
    ) -> Optional[int]:
        return (
            check_request.chapter_number
            or self._to_int(chapter.get("chapter_number"))
            or self._to_int(chapter.get("chapterNumber"))
        )

    def _to_int(self, value: Any) -> Optional[int]:
        if value is None:
            return None
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def _issue(
        self,
        issue_type: str,
        severity: str,
        title: str,
        description: str,
        conflict_chapters: List[Optional[int]],
        suggestion: str
    ) -> Dict[str, Any]:
        clean_chapters = [
            chapter for chapter in conflict_chapters
            if isinstance(chapter, int)
        ]
        return ContinuityIssue(
            issue_type=issue_type,
            severity=severity,
            title=title,
            description=description,
            conflict_chapters=clean_chapters,
            conflict_details=description,
            suggestion=suggestion
        ).dict()

    def _count_by_severity(self, issues: List[Dict[str, Any]]) -> Dict[str, int]:
        counts = {"critical": 0, "major": 0, "minor": 0}
        for issue in issues:
            severity = issue.get("severity")
            if severity in counts:
                counts[severity] += 1
        return counts

    async def _save_continuity_report(
        self,
        check_request: ContinuityCheckRequest,
        chapter: Dict[str, Any],
        issues: List[Dict[str, Any]],
        counts: Dict[str, int]
    ) -> Path:
        report_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" /
            check_request.project_id / "memory" / "continuity"
        )
        report_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
        safe_chapter_id = "".join(
            char if char.isalnum() or char in "-_" else "_"
            for char in check_request.chapter_id
        )
        report_path = report_dir / f"{safe_chapter_id}_{timestamp}.json"
        report = {
            "project_id": check_request.project_id,
            "book_id": check_request.book_id,
            "chapter_id": check_request.chapter_id,
            "chapter_number": self._chapter_number(chapter, check_request),
            "chapter_title": chapter.get("chapter_title") or chapter.get("chapterTitle") or "",
            "checked_at": datetime.now().isoformat(),
            "has_issues": len(issues) > 0,
            "critical_count": counts["critical"],
            "major_count": counts["major"],
            "minor_count": counts["minor"],
            "issues": issues,
        }
        with open(report_path, 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        return report_path

    async def _load_memories(self, query_request: MemoryQueryRequest) -> Dict:
        """加载记忆数据"""
        memories = {}
        project_memory_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / query_request.project_id / "memory"
        )
        book_memory_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / query_request.project_id /
            "books" / query_request.book_id / "memories"
        )
        file_name = {
            "character": "characters.json",
            "characters": "characters.json",
            "setting": "world_settings.json",
            "world_setting": "world_settings.json",
            "world_settings": "world_settings.json",
            "plot": "plots.json",
            "plots": "plots.json",
            "suspense": "suspenses.json",
            "suspenses": "suspenses.json",
            "timeline": "timeline.json",
        }.get(query_request.query_type, f"{query_request.query_type}s.json")

        # 根据查询类型加载对应的记忆文件
        memory_file = project_memory_dir / file_name
        if not memory_file.exists():
            memory_file = book_memory_dir / file_name

        if memory_file.exists():
            with open(memory_file, 'r', encoding='utf-8') as f:
                memories[query_request.query_type] = json.load(f)
        else:
            memories[query_request.query_type] = []

        return memories

    async def _query_memories(self, query_request: MemoryQueryRequest,
                             memories: Dict) -> List[Dict]:
        """查询记忆"""
        results = []
        query_lower = query_request.query.lower()

        for item in memories.get(query_request.query_type, []):
            # 简单的关键词匹配
            if query_lower in json.dumps(item, ensure_ascii=False).lower():
                results.append(item)

        return results
