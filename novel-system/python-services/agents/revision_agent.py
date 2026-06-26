"""
章节返修Agent。

独立执行文档中的 chapter_revision：读取已有章节，按审查/边界/人工意见返修，
归档旧版本，并把返修报告写入 novel/reviews。
"""
import json
from pathlib import Path
from datetime import datetime
from typing import Any, Dict, List, Optional

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from schemas.chapter_schemas import ChapterContent, ChapterRevisionRequest
from schemas.outline_schemas import BookOutlineSchema, ChapterOutlineSchema
from llm.client import LLMClient
from quality import BoundaryChecker
from config import settings
from utils.logger import get_logger


class RevisionAgent(BaseAgent):
    """独立章节返修Agent。"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("RevisionAgent")
        self.supported_tasks = ["chapter_revision"]
        self.llm_client = llm_client or LLMClient()
        self.boundary_checker = BoundaryChecker()
        self.logger = get_logger("RevisionAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        self.metrics["start_time"] = datetime.now()

        try:
            await self.validate_request(request)
            revision_request = ChapterRevisionRequest(**request.parameters)
            project_root = self._project_root(revision_request.project_id)

            outline = self._load_outline(project_root, revision_request.book_id)
            chapter_outline = self._get_chapter_outline(
                outline,
                revision_request.volume_number,
                revision_request.chapter_number,
            )
            future_chapters = self._get_future_chapters(
                outline,
                revision_request.volume_number,
                revision_request.chapter_number,
            )
            boundary_control = self.boundary_checker.build_boundary_control(chapter_outline, future_chapters)
            future_protection = self.boundary_checker.build_future_protection(
                revision_request.chapter_number,
                future_chapters,
            )

            chapter_file = self._resolve_chapter_file(project_root, revision_request)
            chapter_data = self._read_json(chapter_file)
            original_content = ChapterContent(**chapter_data)
            original_snapshot = ChapterContent(**chapter_data)

            pre_boundary = self.boundary_checker.check(
                original_content.content,
                boundary_control,
                future_protection,
            )
            issues = self._collect_issues(revision_request, pre_boundary)
            if not issues:
                issues.append({
                    "severity": "info",
                    "type": "manual_revision",
                    "message": revision_request.user_instruction or "人工触发返修",
                    "evidence": "",
                    "suggestion": revision_request.user_instruction,
                })

            snapshot_path = None
            if revision_request.create_version_snapshot:
                snapshot_path = self._snapshot_chapter(project_root, chapter_file, original_content)

            revised = original_content
            iteration_records: List[Dict[str, Any]] = []
            max_iterations = max(1, revision_request.max_iterations)
            for iteration in range(1, max_iterations + 1):
                revised = await self._revise_once(
                    revision_request=revision_request,
                    chapter_content=revised,
                    chapter_outline=chapter_outline,
                    boundary_control=boundary_control,
                    future_protection=future_protection,
                    issues=issues,
                    iteration=iteration,
                )
                iteration_records.append(revised.revision_history[-1])

                post_boundary = self.boundary_checker.check(
                    revised.content,
                    boundary_control,
                    future_protection,
                )
                if post_boundary.passed:
                    break
                issues = self._collect_issues(revision_request, post_boundary)

            final_boundary = self.boundary_checker.check(
                revised.content,
                boundary_control,
                future_protection,
            )
            revised.boundary_check = final_boundary.to_dict()
            revised.review_status = "reviewed" if final_boundary.passed else "needs_revision"
            revised.review_comments = [issue.get("message", "") for issue in final_boundary.blocking_errors]
            revised.updated_at = datetime.now()

            self._write_chapter(chapter_file, revised)
            review_path = self._write_review_report(
                project_root,
                revision_request,
                original_snapshot,
                revised,
                pre_boundary.to_dict(),
                final_boundary.to_dict(),
                issues,
                snapshot_path,
                iteration_records,
            )

            structured_output = {
                "chapter_id": revised.chapter_id,
                "chapter_title": revised.chapter_title,
                "chapter_path": str(chapter_file),
                "review_path": str(review_path),
                "snapshot_path": str(snapshot_path) if snapshot_path else "",
                "version": revised.version,
                "revision_count": len(revised.revision_history),
                "new_revision_count": len(iteration_records),
                "boundary_passed": final_boundary.passed,
                "boundary_blocking_errors": len(final_boundary.blocking_errors),
                "boundary_warnings": len(final_boundary.warnings),
                "word_count_before": original_content.word_count,
                "word_count_after": revised.word_count,
            }

            return self._build_response(
                request=request,
                status="success" if final_boundary.passed else "partial",
                output_refs=[str(chapter_file), str(review_path)] + ([str(snapshot_path)] if snapshot_path else []),
                structured_output=structured_output,
                warnings=final_boundary.warnings,
                errors=final_boundary.blocking_errors,
            )

        except Exception as exc:
            self.logger.error("Chapter revision failed: %s", exc, exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "CHAPTER_REVISION_ERROR",
                    "message": str(exc),
                    "retryable": True,
                }],
            )

    async def _revise_once(
            self,
            revision_request: ChapterRevisionRequest,
            chapter_content: ChapterContent,
            chapter_outline: ChapterOutlineSchema,
            boundary_control: Dict[str, Any],
            future_protection: Dict[str, Any],
            issues: List[Dict[str, Any]],
            iteration: int) -> ChapterContent:
        original_text = chapter_content.content
        prompt = self._build_revision_prompt(
            revision_request,
            chapter_content,
            chapter_outline,
            boundary_control,
            future_protection,
            issues,
            iteration,
        )
        llm_response = await self.llm_client.generate_with_retry(
            prompt=prompt,
            response_format="text",
            max_retries=3,
            max_tokens=6000,
            temperature=0.55,
        )
        self.metrics["llm_calls"] += 1
        self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
        self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

        revised_text = llm_response["content"].strip() or original_text
        chapter_content.content = revised_text
        chapter_content.word_count = len(revised_text)
        chapter_content.version += 1
        chapter_content.updated_at = datetime.now()
        chapter_content.revision_history.append({
            "iteration": len(chapter_content.revision_history) + 1,
            "task_iteration": iteration,
            "revised_at": datetime.now().isoformat(),
            "agent": "RevisionAgent",
            "trigger_types": sorted({str(issue.get("type", "unknown")) for issue in issues}),
            "issues": issues,
            "user_instruction": revision_request.user_instruction,
            "word_count_before": len(original_text),
            "word_count_after": len(revised_text),
            "status": "revised",
        })
        return chapter_content

    def _build_revision_prompt(
            self,
            revision_request: ChapterRevisionRequest,
            chapter_content: ChapterContent,
            chapter_outline: ChapterOutlineSchema,
            boundary_control: Dict[str, Any],
            future_protection: Dict[str, Any],
            issues: List[Dict[str, Any]],
            iteration: int) -> str:
        issue_lines = "\n".join([
            f"- [{issue.get('severity', 'warning')}] {issue.get('type', '')}: {issue.get('message', '')}\n"
            f"  建议：{issue.get('suggestion', '')}\n"
            f"  证据：{issue.get('evidence', '')}"
            for issue in issues
        ])
        boundary_prompt = self.boundary_checker.prompt_section(boundary_control, future_protection)
        user_instruction = revision_request.user_instruction.strip() or "按审查问题完成必要返修。"

        return f"""
你是一位严格执行大纲、审查意见和章节边界的小说返修编辑。请进行第{iteration}轮返修。

## 人工返修要求

{user_instruction}

## 当前章节

- 标题：{chapter_content.chapter_title}
- 章节：第{chapter_content.volume_number}卷 第{chapter_content.chapter_number}章
- 当前版本：v{chapter_content.version}
- 当前字数：{chapter_content.word_count}

## 章节大纲

- 剧情目标：{chapter_outline.plot_goal}
- 人物发展：{chapter_outline.character_development}
- 信息揭示：{chapter_outline.info_reveal}
- 冲突：{chapter_outline.conflict}
- 章末牵引：{chapter_outline.lead_to_next}

{boundary_prompt}

## 必须处理的问题

{issue_lines}

## 原正文

{chapter_content.content}

## 输出要求

1. 直接输出返修后的完整章节正文。
2. 修复 blocking/error 问题，并尽量处理 warning。
3. 不要写入 must_not_write，不要提前完成后续章纲保护内容。
4. 保留章节标题之外的正文语气和连续性，不要解释修改过程。
"""

    def _collect_issues(self, revision_request: ChapterRevisionRequest, boundary_result) -> List[Dict[str, Any]]:
        issues = list(revision_request.issues or [])
        issues.extend(boundary_result.blocking_errors)
        if revision_request.include_boundary_warnings:
            issues.extend(boundary_result.warnings)
        return issues

    def _load_outline(self, project_root: Path, book_id: str) -> BookOutlineSchema:
        candidates = [
            project_root / "novel" / "outline" / f"{book_id}_outline.json",
            project_root / "outlines" / f"{book_id}_outline.json",
        ]
        outline_file = next((path for path in candidates if path.exists()), None)
        if not outline_file:
            raise FileNotFoundError("Outline not found: " + ", ".join(str(path) for path in candidates))
        return BookOutlineSchema(**self._read_json(outline_file))

    def _get_chapter_outline(
            self,
            outline: BookOutlineSchema,
            volume_number: int,
            chapter_number: int) -> ChapterOutlineSchema:
        volume = next((item for item in outline.volumes if item.volume_number == volume_number), None)
        if not volume:
            raise ValueError(f"Volume {volume_number} not found")
        chapter = next((item for item in volume.chapters if item.chapter_number == chapter_number), None)
        if not chapter:
            raise ValueError(f"Chapter {chapter_number} not found in volume {volume_number}")
        return chapter

    def _get_future_chapters(
            self,
            outline: BookOutlineSchema,
            volume_number: int,
            chapter_number: int) -> List[ChapterOutlineSchema]:
        volume = next((item for item in outline.volumes if item.volume_number == volume_number), None)
        if not volume:
            return []
        return [item for item in volume.chapters if item.chapter_number > chapter_number][:5]

    def _resolve_chapter_file(self, project_root: Path, revision_request: ChapterRevisionRequest) -> Path:
        stage = revision_request.source_stage.lower()
        stage_roots = []
        if stage in {"draft", "auto"}:
            stage_roots.append(project_root / "novel" / "chapters" / "drafts")
        if stage in {"final", "auto"}:
            stage_roots.append(project_root / "novel" / "chapters" / "final")

        candidates = [
            root / revision_request.book_id / f"volume_{revision_request.volume_number}" /
            f"chapter_{revision_request.chapter_number}.json"
            for root in stage_roots
        ]
        if stage == "auto":
            candidates = sorted(candidates, key=lambda path: 0 if "drafts" in str(path) else 1)
        chapter_file = next((path for path in candidates if path.exists()), None)
        if not chapter_file:
            raise FileNotFoundError("Chapter not found: " + ", ".join(str(path) for path in candidates))
        return chapter_file

    def _snapshot_chapter(self, project_root: Path, chapter_file: Path, chapter: ChapterContent) -> Path:
        book_id = chapter.book_id
        volume_dir = f"volume_{chapter.volume_number}"
        versions_dir = project_root / "novel" / "chapters" / "versions" / book_id / volume_dir
        versions_dir.mkdir(parents=True, exist_ok=True)
        archived_at = datetime.now().isoformat()
        snapshot_file = versions_dir / f"chapter_{chapter.chapter_number}_v{chapter.version}_{datetime.now().strftime('%Y%m%d%H%M%S')}.json"
        snapshot_data = chapter.dict()
        snapshot_data["archived_at"] = archived_at
        snapshot_data["source_path"] = str(chapter_file.relative_to(project_root)).replace("\\", "/")
        snapshot_file.write_text(
            json.dumps(snapshot_data, ensure_ascii=False, indent=2, default=str),
            encoding="utf-8",
        )
        return snapshot_file

    def _write_chapter(self, chapter_file: Path, chapter: ChapterContent) -> None:
        data = chapter.dict()
        chapter_file.parent.mkdir(parents=True, exist_ok=True)
        chapter_file.write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        text_file = chapter_file.with_suffix(".txt")
        text_file.write_text(f"{chapter.chapter_title}\n\n{chapter.content}", encoding="utf-8")

    def _write_review_report(
            self,
            project_root: Path,
            revision_request: ChapterRevisionRequest,
            before: ChapterContent,
            after: ChapterContent,
            pre_boundary: Dict[str, Any],
            final_boundary: Dict[str, Any],
            issues: List[Dict[str, Any]],
            snapshot_path: Optional[Path],
            iteration_records: List[Dict[str, Any]]) -> Path:
        reviews_dir = (
            project_root / "novel" / "reviews" / revision_request.book_id /
            f"volume_{revision_request.volume_number}"
        )
        reviews_dir.mkdir(parents=True, exist_ok=True)
        review_file = (
            reviews_dir /
            f"chapter_{revision_request.chapter_number}_revision_{datetime.now().strftime('%Y%m%d%H%M%S')}.json"
        )
        report = {
            "review_type": "chapter_revision",
            "book_id": revision_request.book_id,
            "volume_number": revision_request.volume_number,
            "chapter_number": revision_request.chapter_number,
            "created_at": datetime.now().isoformat(),
            "source_stage": revision_request.source_stage,
            "user_instruction": revision_request.user_instruction,
            "snapshot_path": str(snapshot_path) if snapshot_path else "",
            "version_before": before.version,
            "version_after": after.version,
            "word_count_before": before.word_count,
            "word_count_after": after.word_count,
            "issues": issues,
            "pre_boundary": pre_boundary,
            "final_boundary": final_boundary,
            "iterations": iteration_records,
        }
        review_file.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        return review_file

    def _read_json(self, path: Path) -> Dict[str, Any]:
        return json.loads(path.read_text(encoding="utf-8"))

    def _project_root(self, project_id: str) -> Path:
        return Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
