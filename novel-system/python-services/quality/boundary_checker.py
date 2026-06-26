"""
章节边界检查器。

实现文档中的 must_write / must_not_write / reserved_for_future / stop_point 保护。
当前为轻量规则版：以关键短语和字符 ngram 重叠为主，先保证检查结果能落盘、能被后续返修流程消费。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List


@dataclass
class BoundaryCheckResult:
    boundary_control: Dict[str, Any]
    future_protection: Dict[str, Any]
    blocking_errors: List[Dict[str, Any]]
    warnings: List[Dict[str, Any]]
    info: List[Dict[str, Any]]

    @property
    def passed(self) -> bool:
        return not self.blocking_errors

    def to_dict(self) -> Dict[str, Any]:
        return {
            "passed": self.passed,
            "boundary_control": self.boundary_control,
            "future_protection": self.future_protection,
            "blocking_errors": self.blocking_errors,
            "warnings": self.warnings,
            "info": self.info,
        }


class BoundaryChecker:
    """检查章节是否越界、是否提前消耗后续章纲。"""

    def build_boundary_control(self, chapter_outline: Any, future_chapters: Iterable[Any]) -> Dict[str, Any]:
        future_chapters = list(future_chapters)
        boundary = {
            "core_goal": self._text(
                getattr(chapter_outline, "core_goal", "")
                or getattr(chapter_outline, "plot_goal", "")
            ),
            "must_write": self._list(
                getattr(chapter_outline, "must_write", None)
                or [
                    getattr(chapter_outline, "plot_goal", ""),
                    getattr(chapter_outline, "conflict", ""),
                    getattr(chapter_outline, "info_reveal", ""),
                ]
            ),
            "allowed_progress": self._list(
                getattr(chapter_outline, "allowed_progress", None)
                or [getattr(chapter_outline, "lead_to_next", "")]
            ),
            "must_not_write": self._list(getattr(chapter_outline, "must_not_write", None)),
            "reserved_for_future": self._dict(getattr(chapter_outline, "reserved_for_future", None)),
            "stop_point": self._text(
                getattr(chapter_outline, "stop_point", "")
                or getattr(chapter_outline, "lead_to_next", "")
            ),
            "ending_hook": self._text(
                getattr(chapter_outline, "ending_hook", "")
                or getattr(chapter_outline, "suspense", "")
                or getattr(chapter_outline, "lead_to_next", "")
            ),
        }

        for future in future_chapters:
            key = f"chapter_{getattr(future, 'chapter_number', '')}"
            if key in boundary["reserved_for_future"]:
                continue
            future_event = self._text(
                getattr(future, "core_goal", "")
                or getattr(future, "plot_goal", "")
                or getattr(future, "chapter_title", "")
            )
            if future_event:
                boundary["reserved_for_future"][key] = future_event

        return boundary

    def build_future_protection(self, current_chapter: int, future_chapters: Iterable[Any]) -> Dict[str, Any]:
        protected = []
        for future in future_chapters:
            chapter_number = getattr(future, "chapter_number", None)
            if not chapter_number:
                continue
            core_event = self._text(
                getattr(future, "core_goal", "")
                or getattr(future, "plot_goal", "")
                or getattr(future, "conflict", "")
            )
            if not core_event:
                continue
            protected.append({
                "chapter": chapter_number,
                "core_event": core_event,
                "protection_level": "critical" if chapter_number == current_chapter + 1 else "high",
                "reason": f"第{chapter_number}章核心内容，不可在第{current_chapter}章提前完成",
            })
        return {
            "current_chapter": current_chapter,
            "protected_content": protected,
            "allowed_foreshadowing": [],
        }

    def check(
        self,
        chapter_content: str,
        boundary_control: Dict[str, Any],
        future_protection: Dict[str, Any],
    ) -> BoundaryCheckResult:
        blocking_errors: List[Dict[str, Any]] = []
        warnings: List[Dict[str, Any]] = []
        info: List[Dict[str, Any]] = []

        for forbidden in boundary_control.get("must_not_write", []):
            if self._contains_event(chapter_content, forbidden):
                blocking_errors.append(self._issue(
                    "error",
                    "forbidden_content",
                    f"章节包含禁止内容：{forbidden}",
                    self._evidence(chapter_content, forbidden),
                    "删除该情节，保留给后续章节或大纲指定位置。",
                ))

        for future_chapter, future_content in boundary_control.get("reserved_for_future", {}).items():
            if self._contains_event(chapter_content, future_content):
                blocking_errors.append(self._issue(
                    "error",
                    "future_content_violation",
                    f"提前完成了{future_chapter}的内容：{future_content}",
                    self._evidence(chapter_content, future_content),
                    f"将该情节保留给{future_chapter}，本章只做铺垫。",
                ))

        for item in future_protection.get("protected_content", []):
            event = item.get("core_event", "")
            if event and self._contains_event(chapter_content, event):
                blocking_errors.append(self._issue(
                    "error",
                    "future_outline_violation",
                    f"侵占后续第{item.get('chapter')}章核心事件：{event}",
                    self._evidence(chapter_content, event),
                    "删去已完成的后续事件，只保留不构成结果的暗示。",
                ))

        completed_goals = []
        missing_goals = []
        for goal in boundary_control.get("must_write", []):
            if not goal:
                continue
            if self._contains_event(chapter_content, goal, strict=False):
                completed_goals.append(goal)
            else:
                missing_goals.append(goal)

        if missing_goals:
            warnings.append(self._issue(
                "warning",
                "incomplete_goals",
                "本章目标可能未充分完成：" + "；".join(missing_goals),
                "",
                "补充缺失目标对应的动作、冲突或信息增量。",
            ))

        stop_point = boundary_control.get("stop_point", "")
        if stop_point and not self._near_ending(chapter_content, stop_point):
            warnings.append(self._issue(
                "warning",
                "stop_point_mismatch",
                f"章末可能没有停在指定停止点附近：{stop_point}",
                self._ending_excerpt(chapter_content),
                "调整结尾，让正文停在 stop_point 附近并保留牵引。",
            ))

        hook = boundary_control.get("ending_hook", "")
        if hook and not self._near_ending(chapter_content, hook):
            info.append(self._issue(
                "info",
                "ending_hook_not_obvious",
                f"章末钩子不明显：{hook}",
                self._ending_excerpt(chapter_content),
                "可强化最后一段的悬念或下一章牵引。",
            ))

        info.append({
            "severity": "info",
            "type": "boundary_summary",
            "message": f"已检查 must_write {len(completed_goals)}/{len(boundary_control.get('must_write', []))}，后续保护 {len(future_protection.get('protected_content', []))} 项。",
            "evidence": "",
            "suggestion": "",
        })

        return BoundaryCheckResult(
            boundary_control=boundary_control,
            future_protection=future_protection,
            blocking_errors=blocking_errors,
            warnings=warnings,
            info=info,
        )

    def prompt_section(self, boundary_control: Dict[str, Any], future_protection: Dict[str, Any]) -> str:
        protected_lines = [
            f"- 第{item['chapter']}章：{item['core_event']}（{item['protection_level']}）"
            for item in future_protection.get("protected_content", [])
        ]
        return f"""
## 章节边界控制（严格遵守）

- 本章核心目标：{boundary_control.get('core_goal', '')}
- 必须写：{self._format_list(boundary_control.get('must_write', []))}
- 可轻微铺垫但不得完成：{self._format_list(boundary_control.get('allowed_progress', []))}
- 禁止写：{self._format_list(boundary_control.get('must_not_write', []))}
- 停止点：{boundary_control.get('stop_point', '')}
- 章末钩子：{boundary_control.get('ending_hook', '')}

### 后续章纲保护
以下内容严禁在本章完成，只能至多暗示：
{chr(10).join(protected_lines) if protected_lines else "- 暂无后续保护项"}
"""

    def _contains_event(self, content: str, event: str, strict: bool = True) -> bool:
        content_tokens = self._tokens(content)
        event_tokens = self._tokens(event)
        if not event_tokens:
            return False
        if event and event in content:
            return True
        overlap = len(content_tokens & event_tokens) / max(len(event_tokens), 1)
        threshold = 0.72 if strict else 0.45
        return overlap >= threshold and len(event_tokens) >= 2

    def _near_ending(self, content: str, event: str) -> bool:
        ending = self._ending_excerpt(content, 420)
        return self._contains_event(ending, event, strict=False)

    def _tokens(self, text: str) -> set[str]:
        text = self._text(text)
        words = re.findall(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]{2,}", text)
        tokens = set(words)
        chinese = "".join(re.findall(r"[\u4e00-\u9fff]", text))
        tokens.update(chinese[i:i + 2] for i in range(max(0, len(chinese) - 1)))
        return {token for token in tokens if token.strip()}

    def _evidence(self, content: str, event: str) -> str:
        if event and event in content:
            index = content.find(event)
            return content[max(0, index - 60): index + len(event) + 60]
        return self._ending_excerpt(content)

    def _ending_excerpt(self, content: str, size: int = 240) -> str:
        return self._text(content)[-size:]

    def _issue(self, severity: str, issue_type: str, message: str, evidence: str, suggestion: str) -> Dict[str, Any]:
        return {
            "severity": severity,
            "type": issue_type,
            "message": message,
            "evidence": evidence,
            "suggestion": suggestion,
        }

    def _list(self, value: Any) -> List[str]:
        if not value:
            return []
        if isinstance(value, list):
            return [self._text(item) for item in value if self._text(item)]
        return [self._text(value)]

    def _dict(self, value: Any) -> Dict[str, str]:
        if not value or not isinstance(value, dict):
            return {}
        return {self._text(key): self._text(child) for key, child in value.items() if self._text(child)}

    def _text(self, value: Any) -> str:
        return "" if value is None else str(value).strip()

    def _format_list(self, values: List[str]) -> str:
        return "；".join(values) if values else "无"
