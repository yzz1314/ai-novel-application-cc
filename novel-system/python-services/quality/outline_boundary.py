"""
大纲章节边界补齐工具。

用于把文档要求的 core_goal / must_write / allowed_progress / must_not_write /
reserved_for_future / stop_point / ending_hook 稳定写入章纲。它不依赖 LLM，
主要作为生成后的兜底和旧大纲修复能力。
"""
from __future__ import annotations

from typing import Any, Dict, Iterable, List, Tuple


class OutlineBoundaryCompleter:
    """为书籍大纲补齐章节边界字段。"""

    BOUNDARY_FIELDS = [
        "core_goal",
        "must_write",
        "allowed_progress",
        "must_not_write",
        "reserved_for_future",
        "stop_point",
        "ending_hook",
    ]

    def complete_outline(self, outline: Any, overwrite: bool = False) -> Dict[str, Any]:
        """原地补齐大纲，返回补齐统计。"""
        volumes = self._get(outline, "volumes") or []
        total_chapters = 0
        changed_chapters: List[Dict[str, Any]] = []

        for volume_index, volume in enumerate(volumes, start=1):
            chapters = list(self._get(volume, "chapters") or [])
            for chapter_index, chapter in enumerate(chapters):
                total_chapters += 1
                before_missing = self.missing_fields(chapter)
                before_values = {
                    field: self._get(chapter, field)
                    for field in self.BOUNDARY_FIELDS
                }
                changed = self.complete_chapter(chapter, chapters[chapter_index + 1:], overwrite=overwrite)
                after_missing = self.missing_fields(chapter)
                if changed:
                    changed_fields = [
                        field for field in self.BOUNDARY_FIELDS
                        if before_values.get(field) != self._get(chapter, field)
                    ]
                    changed_chapters.append({
                        "volume": self._get(volume, "volume_number") or volume_index,
                        "chapter": self._get(chapter, "chapter_number") or chapter_index + 1,
                        "filled_fields": before_missing,
                        "changed_fields": changed_fields,
                        "remaining_missing_fields": after_missing,
                    })

        return {
            "total_chapters": total_chapters,
            "overwrite": overwrite,
            "changed_chapter_count": len(changed_chapters),
            "changed_chapters": changed_chapters,
            "chapters_with_boundary": total_chapters - sum(
                1 for volume in volumes
                for chapter in (self._get(volume, "chapters") or [])
                if self.missing_fields(chapter)
            ),
        }

    def complete_chapter(self, chapter: Any, future_chapters: Iterable[Any], overwrite: bool = False) -> bool:
        """原地补齐单章边界字段。"""
        future_chapters = list(future_chapters)
        changed = False

        plot_goal = self._text(self._get(chapter, "plot_goal"))
        conflict = self._text(self._get(chapter, "conflict"))
        info_reveal = self._text(self._get(chapter, "info_reveal"))
        lead_to_next = self._text(self._get(chapter, "lead_to_next"))
        suspense = self._text(self._get(chapter, "suspense"))
        title = self._text(self._get(chapter, "chapter_title"))
        chapter_number = self._get(chapter, "chapter_number") or ""
        future_summaries = self._future_summaries(future_chapters)

        core_goal = self._text(self._get(chapter, "core_goal"))
        if overwrite or not core_goal:
            core_goal = plot_goal or conflict or f"完成第{chapter_number}章《{title}》的核心推进"
            changed |= self._set(chapter, "core_goal", core_goal)

        if overwrite or not self._as_list(self._get(chapter, "must_write")):
            must_write = self._dedupe([plot_goal, conflict, info_reveal, core_goal])
            changed |= self._set(chapter, "must_write", must_write)

        if overwrite or not self._as_list(self._get(chapter, "allowed_progress")):
            allowed = self._dedupe([
                lead_to_next,
                suspense,
                self._foreshadowing_line(future_summaries[0]) if future_summaries else "",
            ])
            changed |= self._set(chapter, "allowed_progress", allowed)

        if overwrite or not self._as_list(self._get(chapter, "must_not_write")):
            forbidden = [
                f"不得提前完成第{item['chapter']}章：{item['summary']}"
                for item in future_summaries[:3]
            ]
            if not forbidden:
                forbidden = ["不得违背 Project Soul、已写终稿记忆和既定 Canon"]
            changed |= self._set(chapter, "must_not_write", forbidden)

        reserved = self._as_dict(self._get(chapter, "reserved_for_future"))
        if overwrite or not reserved:
            reserved = {
                f"chapter_{item['chapter']}": item["summary"]
                for item in future_summaries[:5]
            }
            if not reserved:
                reserved = {
                    "long_term_suspense": suspense or lead_to_next or "保留未成熟的长线悬念，不在本章彻底解释"
                }
            changed |= self._set(chapter, "reserved_for_future", reserved)

        if overwrite or not self._text(self._get(chapter, "stop_point")):
            stop_point = lead_to_next or suspense or self._stop_point_from_future(future_summaries) or core_goal
            changed |= self._set(chapter, "stop_point", stop_point)

        if overwrite or not self._text(self._get(chapter, "ending_hook")):
            ending_hook = suspense or lead_to_next or self._ending_hook_from_future(future_summaries) or core_goal
            changed |= self._set(chapter, "ending_hook", ending_hook)

        return changed

    def missing_fields(self, chapter: Any) -> List[str]:
        missing = []
        for field in self.BOUNDARY_FIELDS:
            value = self._get(chapter, field)
            if field in {"must_write", "allowed_progress", "must_not_write"}:
                if not self._as_list(value):
                    missing.append(field)
            elif field == "reserved_for_future":
                if not self._as_dict(value):
                    missing.append(field)
            elif not self._text(value):
                missing.append(field)
        return missing

    def _future_summaries(self, future_chapters: Iterable[Any]) -> List[Dict[str, str]]:
        summaries = []
        for future in future_chapters:
            chapter_number = self._get(future, "chapter_number")
            title = self._text(self._get(future, "chapter_title"))
            event = self._text(
                self._get(future, "core_goal")
                or self._get(future, "plot_goal")
                or self._get(future, "conflict")
                or self._get(future, "chapter_title")
            )
            if title and event and title not in event:
                summary = f"《{title}》：{event}"
            else:
                summary = event or title
            if chapter_number and summary:
                summaries.append({"chapter": str(chapter_number), "summary": summary})
        return summaries

    def _foreshadowing_line(self, future_summary: Dict[str, str]) -> str:
        return f"可为第{future_summary['chapter']}章“{future_summary['summary']}”埋设线索，但不得完成结果"

    def _stop_point_from_future(self, future_summaries: List[Dict[str, str]]) -> str:
        if not future_summaries:
            return ""
        item = future_summaries[0]
        return f"停在引出第{item['chapter']}章“{item['summary']}”之前"

    def _ending_hook_from_future(self, future_summaries: List[Dict[str, str]]) -> str:
        if not future_summaries:
            return ""
        item = future_summaries[0]
        return f"留下第{item['chapter']}章“{item['summary']}”的追读钩子"

    def _get(self, target: Any, field: str) -> Any:
        camel = self._to_camel(field)
        if isinstance(target, dict):
            if field in target:
                return target.get(field)
            return target.get(camel)
        return getattr(target, field, getattr(target, camel, None))

    def _set(self, target: Any, field: str, value: Any) -> bool:
        if isinstance(target, dict):
            key = field if field in target or self._to_camel(field) not in target else self._to_camel(field)
            if target.get(key) == value:
                return False
            target[key] = value
            return True
        if getattr(target, field, None) == value:
            return False
        setattr(target, field, value)
        return True

    def _as_list(self, value: Any) -> List[str]:
        if not value:
            return []
        if isinstance(value, list):
            return [self._text(item) for item in value if self._text(item)]
        return [self._text(value)]

    def _as_dict(self, value: Any) -> Dict[str, str]:
        if not value or not isinstance(value, dict):
            return {}
        return {
            self._text(key): self._text(child)
            for key, child in value.items()
            if self._text(key) and self._text(child)
        }

    def _dedupe(self, values: Iterable[str]) -> List[str]:
        result = []
        seen = set()
        for value in values:
            text = self._text(value)
            if not text or text in seen:
                continue
            seen.add(text)
            result.append(text)
        return result

    def _text(self, value: Any) -> str:
        return "" if value is None else str(value).strip()

    def _to_camel(self, field: str) -> str:
        parts = field.split("_")
        return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])
