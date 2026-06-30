"""
大纲审查Agent

对已生成的大纲和 Project Soul 做规则审查，并在有模型配置时补充 LLM 语义质量评估。
规则审查负责稳定检查章节边界字段、基础结构完整性和明显章纲冲突；LLM 评估负责判断
题材一致性、主线推进、人物/世界观约束、卷章节奏和读者牵引。
"""
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from quality.outline_boundary import OutlineBoundaryCompleter
from llm.client import LLMClient
from config import settings
from utils.logger import get_logger


class OutlineReviewAgent(BaseAgent):
    """大纲审查Agent"""

    REQUIRED_OUTLINE_FIELDS = [
        "book_id",
        "book_title",
        "genre",
        "core_concept",
        "world_view",
        "main_conflict",
        "volumes",
    ]
    REQUIRED_CHAPTER_FIELDS = [
        "chapter_number",
        "chapter_title",
        "plot_goal",
        "target_word_count",
    ]
    REQUIRED_BOUNDARY_FIELDS = [
        "core_goal",
        "must_write",
        "allowed_progress",
        "must_not_write",
        "reserved_for_future",
        "stop_point",
        "ending_hook",
    ]

    def __init__(self, llm_client: Optional[LLMClient] = None):
        super().__init__("OutlineReviewAgent")
        self.supported_tasks = ["outline_review"]
        self.boundary_completer = OutlineBoundaryCompleter()
        self.llm_client = llm_client or LLMClient()
        self.logger = get_logger("OutlineReviewAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        self.metrics["start_time"] = datetime.now()

        try:
            await self.validate_request(request)
            project_id = request.project_id
            book_id = self._resolve_book_id(request)
            outline_path = self._resolve_outline_path(project_id, book_id)
            outline = self._read_json(outline_path)
            book_id = str(outline.get("book_id") or book_id or self._book_id_from_path(outline_path))
            soul_path = self._resolve_soul_path(project_id)
            project_soul = soul_path.read_text(encoding="utf-8") if soul_path.exists() else ""
            auto_fix = self._bool_option(request, "auto_fix", "autoFix", default=False)
            overwrite_boundary = self._bool_option(
                request,
                "overwrite_boundary",
                "overwriteBoundary",
                default=False
            )
            fix_result: Dict[str, Any] = {"applied": False}
            output_refs: List[str] = []

            findings, metrics = self._review_outline(outline, project_soul, soul_path)

            if auto_fix and (overwrite_boundary or self._has_missing_boundary(findings)):
                fix_result = self._auto_fix_outline(
                    project_id,
                    book_id,
                    outline_path,
                    outline,
                    overwrite_boundary
                )
                output_refs.extend(fix_result.get("output_refs", []))
                outline = self._read_json(outline_path)
                findings, metrics = self._review_outline(outline, project_soul, soul_path)

            rule_errors = [item for item in findings if item["severity"] == "error"]
            rule_warnings = [item for item in findings if item["severity"] == "warning"]
            rule_score = self._score(rule_errors, rule_warnings, metrics)

            semantic_quality = await self._evaluate_semantic_quality_if_enabled(
                request,
                outline,
                project_soul,
                metrics,
            )
            findings.extend(self._semantic_findings(semantic_quality))

            errors = [item for item in findings if item["severity"] == "error"]
            warnings = [item for item in findings if item["severity"] == "warning"]
            infos = [item for item in findings if item["severity"] == "info"]
            score = self._combined_score(rule_score, semantic_quality)
            semantic_needs_revision = (
                semantic_quality.get("attempted")
                and semantic_quality.get("status") == "needs_revision"
            )
            status = "passed" if not errors and score >= 80 and not semantic_needs_revision else "needs_revision"
            report = {
                "review_type": "outline_review",
                "project_id": project_id,
                "book_id": book_id,
                "outline_path": self._relative(project_id, outline_path),
                "project_soul_path": self._relative(project_id, soul_path) if soul_path.exists() else "",
                "status": status,
                "score": score,
                "rule_score": rule_score,
                "summary": self._summary(status, score, errors, warnings, semantic_quality),
                "metrics": metrics,
                "semantic_quality": semantic_quality,
                "auto_fix": fix_result,
                "finding_count": len(findings),
                "error_count": len(errors),
                "warning_count": len(warnings),
                "info_count": len(infos),
                "findings": findings,
                "reviewed_at": datetime.now().isoformat(),
            }
            report_path = self._write_report(project_id, book_id, report)
            report["report_path"] = self._relative(project_id, report_path)
            output_refs.append(str(report_path))

            return self._build_response(
                request=request,
                status="success" if not errors else "partial",
                output_refs=output_refs,
                structured_output=report,
                warnings=[
                    {"code": item["code"], "message": item["message"], "path": item.get("path", "")}
                    for item in warnings
                ],
                errors=[
                    {"code": item["code"], "message": item["message"], "path": item.get("path", ""), "retryable": False}
                    for item in errors
                ],
            )
        except Exception as exc:
            self.logger.error(f"Outline review failed: {exc}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "OUTLINE_REVIEW_ERROR",
                    "message": str(exc),
                    "retryable": True,
                }],
            )

    def _review_outline(
            self,
            outline: Dict[str, Any],
            project_soul: str,
            soul_path: Path) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        findings: List[Dict[str, Any]] = []
        metrics: Dict[str, Any] = {}
        findings.extend(self._check_outline_structure(outline))
        findings.extend(self._check_project_soul(project_soul, soul_path))
        chapter_metrics, chapter_findings = self._check_chapters(outline)
        metrics.update(chapter_metrics)
        findings.extend(chapter_findings)
        findings.extend(self._check_duplicate_chapter_numbers(outline))
        findings.extend(self._check_boundary_conflicts(outline))
        return findings, metrics

    async def _evaluate_semantic_quality_if_enabled(
            self,
            request: AgentRequest,
            outline: Dict[str, Any],
            project_soul: str,
            metrics: Dict[str, Any]) -> Dict[str, Any]:
        enabled = self._semantic_review_enabled(request)
        if not enabled:
            return {
                "enabled": False,
                "attempted": False,
                "status": "skipped",
                "summary": "未启用 LLM 语义质量评估。",
            }

        prompt = self._build_semantic_quality_prompt(outline, project_soul, metrics)
        try:
            llm_response = await self.llm_client.generate_with_retry(
                prompt=prompt,
                response_format="json",
                max_retries=2,
                task_type="outline_review",
                max_tokens=1800,
                temperature=0.2,
            )
            self.metrics["llm_calls"] += 1
            self.metrics["input_tokens"] += int(llm_response.get("usage", {}).get("prompt_tokens", 0) or 0)
            self.metrics["output_tokens"] += int(llm_response.get("usage", {}).get("completion_tokens", 0) or 0)
            payload = self._parse_semantic_quality_payload(llm_response.get("content"))
            payload.update({
                "enabled": True,
                "attempted": True,
                "source": "llm_gateway",
                "model_gateway": self.llm_client.current_model_metadata(),
                "usage": llm_response.get("usage", {}),
            })
            return payload
        except Exception as exc:
            self.logger.warning(f"Outline semantic quality evaluation skipped after LLM failure: {exc}")
            return {
                "enabled": True,
                "attempted": True,
                "status": "unavailable",
                "score": None,
                "pass_review": None,
                "source": "llm_gateway",
                "summary": "LLM 语义质量评估不可用，已保留规则审查结果。",
                "error": str(exc),
                "model_gateway": self.llm_client.current_model_metadata(),
            }

    def _semantic_review_enabled(self, request: AgentRequest) -> bool:
        for source in (request.parameters, request.config, request.input_refs):
            for key in (
                    "semantic_quality",
                    "semanticQuality",
                    "llm_quality",
                    "llmQuality",
                    "enable_llm_quality",
                    "enableLlmQuality"):
                if key in source:
                    value = source.get(key)
                    if isinstance(value, bool):
                        return value
                    return str(value).lower() in {"true", "1", "yes", "y", "on"}
        return bool(request.model_profile_id)

    def _build_semantic_quality_prompt(
            self,
            outline: Dict[str, Any],
            project_soul: str,
            metrics: Dict[str, Any]) -> str:
        outline_snapshot = self._outline_semantic_snapshot(outline)
        return f"""
你是一位资深网文总编，请对以下小说大纲做“真实 LLM 语义质量评估”。

请不要重复规则校验，而是判断这个大纲是否足以进入正文创作：
1. Project Soul 与大纲是否一致
2. 主线冲突、人物目标、世界观规则是否清晰
3. 分卷/章节推进是否有因果递进，不只是事件罗列
4. 章节边界是否能防止提前消耗后续章纲
5. 爽点、悬念、章末牵引是否有可持续性

## 规则审查指标
{json.dumps(metrics, ensure_ascii=False, indent=2)}

## Project Soul
{project_soul[:3000] if project_soul else "未提供"}

## 大纲摘要
{json.dumps(outline_snapshot, ensure_ascii=False, indent=2)}

请以 JSON 返回，字段必须包含：
{{
  "score": 0-100,
  "status": "passed|needs_revision",
  "summary": "一句话结论",
  "dimensions": {{
    "soul_alignment": 0-10,
    "plot_causality": 0-10,
    "character_arc": 0-10,
    "worldbuilding_constraints": 0-10,
    "chapter_boundary_control": 0-10,
    "reader_hook": 0-10
  }},
  "strengths": ["优点"],
  "issues": [
    {{"severity": "warning|error", "code": "问题代码", "message": "问题说明", "path": "outline路径或章节"}}
  ],
  "suggestions": ["修改建议"],
  "pass_review": true
}}
"""

    def _outline_semantic_snapshot(self, outline: Dict[str, Any]) -> Dict[str, Any]:
        volumes = []
        for volume in outline.get("volumes") or []:
            if not isinstance(volume, dict):
                continue
            chapters = []
            for chapter in (volume.get("chapters") or [])[:12]:
                if not isinstance(chapter, dict):
                    continue
                chapters.append({
                    "chapter_number": chapter.get("chapter_number") or chapter.get("chapterNumber"),
                    "chapter_title": chapter.get("chapter_title") or chapter.get("chapterTitle"),
                    "plot_goal": chapter.get("plot_goal") or chapter.get("plotGoal"),
                    "core_goal": chapter.get("core_goal") or chapter.get("coreGoal"),
                    "stop_point": chapter.get("stop_point") or chapter.get("stopPoint"),
                    "ending_hook": chapter.get("ending_hook") or chapter.get("endingHook"),
                    "must_not_write": chapter.get("must_not_write") or chapter.get("mustNotWrite"),
                    "reserved_for_future": chapter.get("reserved_for_future") or chapter.get("reservedForFuture"),
                })
            volumes.append({
                "volume_number": volume.get("volume_number") or volume.get("volumeNumber"),
                "volume_title": volume.get("volume_title") or volume.get("volumeTitle"),
                "main_conflict": volume.get("main_conflict") or volume.get("mainConflict"),
                "chapters": chapters,
            })
        return {
            "book_id": outline.get("book_id") or outline.get("bookId"),
            "book_title": outline.get("book_title") or outline.get("bookTitle"),
            "genre": outline.get("genre"),
            "core_concept": outline.get("core_concept") or outline.get("coreConcept"),
            "world_view": outline.get("world_view") or outline.get("worldView"),
            "main_conflict": outline.get("main_conflict") or outline.get("mainConflict"),
            "characters": (outline.get("characters") or [])[:8],
            "long_term_suspense": (outline.get("long_term_suspense") or outline.get("longTermSuspense") or [])[:8],
            "volumes": volumes[:8],
        }

    def _parse_semantic_quality_payload(self, content: Any) -> Dict[str, Any]:
        try:
            payload = json.loads(str(content or "{}"))
        except json.JSONDecodeError:
            payload = {"summary": str(content or "").strip()}

        score = self._clamp_int(payload.get("score"), 0, 100, default=0)
        status = str(payload.get("status") or ("passed" if score >= 80 else "needs_revision"))
        dimensions = payload.get("dimensions") if isinstance(payload.get("dimensions"), dict) else {}
        issues = payload.get("issues") if isinstance(payload.get("issues"), list) else []
        strengths = payload.get("strengths") if isinstance(payload.get("strengths"), list) else []
        suggestions = payload.get("suggestions") if isinstance(payload.get("suggestions"), list) else []
        pass_review = payload.get("pass_review")
        if pass_review is None:
            pass_review = status == "passed" and score >= 80

        return {
            "score": score,
            "status": "passed" if status == "passed" and bool(pass_review) else "needs_revision",
            "summary": str(payload.get("summary") or "LLM 语义质量评估完成。"),
            "dimensions": dimensions,
            "strengths": strengths,
            "issues": issues,
            "suggestions": suggestions,
            "pass_review": bool(pass_review),
        }

    def _semantic_findings(self, semantic_quality: Dict[str, Any]) -> List[Dict[str, Any]]:
        if not semantic_quality.get("enabled") or not semantic_quality.get("attempted"):
            return []
        if semantic_quality.get("status") == "unavailable":
            return [self._finding(
                "warning",
                "semantic_quality_unavailable",
                semantic_quality.get("summary") or "LLM 语义质量评估不可用",
                "semantic_quality",
            )]

        findings: List[Dict[str, Any]] = []
        for index, issue in enumerate(semantic_quality.get("issues") or []):
            if not isinstance(issue, dict):
                continue
            severity = str(issue.get("severity") or "warning").lower()
            if severity not in {"info", "warning", "error"}:
                severity = "warning"
            findings.append(self._finding(
                severity,
                str(issue.get("code") or "semantic_quality_issue"),
                str(issue.get("message") or issue.get("description") or "LLM 语义质量评估发现问题"),
                str(issue.get("path") or f"semantic_quality.issues[{index}]"),
            ))
        if semantic_quality.get("status") == "needs_revision" and not findings:
            findings.append(self._finding(
                "warning",
                "semantic_quality_needs_revision",
                semantic_quality.get("summary") or "LLM 语义质量评估建议修改大纲",
                "semantic_quality",
            ))
        return findings

    def _has_missing_boundary(self, findings: List[Dict[str, Any]]) -> bool:
        return any(item.get("code") == "missing_boundary_fields" for item in findings)

    def _auto_fix_outline(
            self,
            project_id: str,
            book_id: str,
            outline_path: Path,
            outline: Dict[str, Any],
            overwrite_boundary: bool) -> Dict[str, Any]:
        snapshot_path = self._archive_outline(project_id, book_id, outline_path, outline)
        completion = self.boundary_completer.complete_outline(outline, overwrite=overwrite_boundary)
        outline["updated_at"] = datetime.now().isoformat()
        outline["boundary_completed_at"] = outline["updated_at"]
        outline["boundary_completion"] = completion
        outline["previous_snapshot_path"] = self._relative(project_id, snapshot_path)
        with outline_path.open("w", encoding="utf-8") as handle:
            json.dump(outline, handle, ensure_ascii=False, indent=2, default=str)
        report_path = self._write_fix_report(project_id, book_id, outline_path, snapshot_path, completion)
        return {
            "applied": True,
            "snapshot_path": self._relative(project_id, snapshot_path),
            "report_path": self._relative(project_id, report_path),
            "overwrite_boundary": overwrite_boundary,
            "completion": completion,
            "output_refs": [str(snapshot_path), str(report_path), str(outline_path)],
        }

    def _resolve_book_id(self, request: AgentRequest) -> str:
        for source in (request.input_refs, request.parameters, request.config):
            value = source.get("book_id") or source.get("bookId")
            if value:
                return str(value)
        return "default"

    def _resolve_outline_path(self, project_id: str, book_id: str) -> Path:
        root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        outline_dirs = [root / "novel" / "outline", root / "outlines"]
        if book_id and book_id != "default":
            for directory in outline_dirs:
                candidate = directory / f"{book_id}_outline.json"
                if candidate.exists():
                    return candidate
        candidates: List[Path] = []
        for directory in outline_dirs:
            if directory.exists():
                candidates.extend(directory.glob("*_outline.json"))
        if not candidates:
            raise FileNotFoundError(f"Outline not found for project {project_id}")
        return sorted(candidates, key=lambda path: path.stat().st_mtime, reverse=True)[0]

    def _resolve_soul_path(self, project_id: str) -> Path:
        return Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "novel" / "soul" / "project_soul.md"

    def _read_json(self, path: Path) -> Dict[str, Any]:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)

    def _check_outline_structure(self, outline: Dict[str, Any]) -> List[Dict[str, Any]]:
        findings: List[Dict[str, Any]] = []
        for field in self.REQUIRED_OUTLINE_FIELDS:
            if self._is_empty(outline.get(field)):
                findings.append(self._finding(
                    "error",
                    "missing_outline_field",
                    f"大纲缺少必填字段: {field}",
                    field,
                ))
        volumes = outline.get("volumes")
        if not isinstance(volumes, list) or not volumes:
            findings.append(self._finding("error", "missing_volumes", "大纲缺少分卷信息", "volumes"))
        return findings

    def _check_project_soul(self, content: str, soul_path: Path) -> List[Dict[str, Any]]:
        findings: List[Dict[str, Any]] = []
        if not content.strip():
            findings.append(self._finding(
                "error",
                "missing_project_soul",
                "Project Soul 不存在或为空",
                str(soul_path),
            ))
            return findings
        if len(content.strip()) < 300:
            findings.append(self._finding(
                "warning",
                "thin_project_soul",
                "Project Soul 内容偏短，可能不足以约束后续创作",
                str(soul_path),
            ))
        required_keywords = ["核心", "不可", "人物", "世界"]
        missing = [keyword for keyword in required_keywords if keyword not in content]
        if missing:
            findings.append(self._finding(
                "warning",
                "project_soul_missing_sections",
                "Project Soul 可能缺少关键约束关键词: " + ",".join(missing),
                str(soul_path),
            ))
        return findings

    def _check_chapters(self, outline: Dict[str, Any]) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
        findings: List[Dict[str, Any]] = []
        total_chapters = 0
        chapters_with_boundary = 0
        missing_boundary_count = 0
        thin_chapter_count = 0

        for vol_index, volume in enumerate(outline.get("volumes") or [], start=1):
            chapters = volume.get("chapters") if isinstance(volume, dict) else None
            if not isinstance(chapters, list) or not chapters:
                findings.append(self._finding(
                    "error",
                    "missing_volume_chapters",
                    f"第{vol_index}卷缺少章节列表",
                    f"volumes[{vol_index - 1}].chapters",
                ))
                continue
            for chapter_index, chapter in enumerate(chapters, start=1):
                total_chapters += 1
                path = f"volumes[{vol_index - 1}].chapters[{chapter_index - 1}]"
                if not isinstance(chapter, dict):
                    findings.append(self._finding("error", "invalid_chapter", "章节条目不是对象", path))
                    continue
                for field in self.REQUIRED_CHAPTER_FIELDS:
                    if self._is_empty(chapter.get(field)):
                        findings.append(self._finding(
                            "error",
                            "missing_chapter_field",
                            f"章节缺少必填字段: {field}",
                            f"{path}.{field}",
                        ))
                if len(str(chapter.get("plot_goal") or "")) < 12:
                    thin_chapter_count += 1
                    findings.append(self._finding(
                        "warning",
                        "thin_plot_goal",
                        "章节剧情目标过短，可能不足以指导正文创作",
                        f"{path}.plot_goal",
                    ))
                missing_boundary = self.boundary_completer.missing_fields(chapter)
                if missing_boundary:
                    missing_boundary_count += 1
                    findings.append(self._finding(
                        "warning",
                        "missing_boundary_fields",
                        "章节缺少边界字段: " + ",".join(missing_boundary),
                        path,
                    ))
                else:
                    chapters_with_boundary += 1

        metrics = {
            "total_chapters": total_chapters,
            "chapters_with_boundary": chapters_with_boundary,
            "missing_boundary_count": missing_boundary_count,
            "thin_chapter_count": thin_chapter_count,
        }
        return metrics, findings

    def _check_duplicate_chapter_numbers(self, outline: Dict[str, Any]) -> List[Dict[str, Any]]:
        findings: List[Dict[str, Any]] = []
        seen = set()
        for vol_index, volume in enumerate(outline.get("volumes") or [], start=1):
            for chapter in (volume.get("chapters") or []):
                number = chapter.get("chapter_number") if isinstance(chapter, dict) else None
                key = (vol_index, number)
                if number in (None, ""):
                    continue
                if key in seen:
                    findings.append(self._finding(
                        "error",
                        "duplicate_chapter_number",
                        f"第{vol_index}卷存在重复章节号: {number}",
                        f"volumes[{vol_index - 1}].chapters",
                    ))
                seen.add(key)
        return findings

    def _check_boundary_conflicts(self, outline: Dict[str, Any]) -> List[Dict[str, Any]]:
        findings: List[Dict[str, Any]] = []
        for vol_index, volume in enumerate(outline.get("volumes") or [], start=1):
            chapters = volume.get("chapters") or []
            future_reserved: Dict[str, int] = {}
            for chapter in chapters:
                if not isinstance(chapter, dict):
                    continue
                chapter_number = chapter.get("chapter_number")
                must_write = set(self._as_list(chapter.get("must_write")))
                for item, owner in future_reserved.items():
                    if owner > chapter_number and item in must_write:
                        findings.append(self._finding(
                            "warning",
                            "reserved_content_written_early",
                            f"第{chapter_number}章疑似提前写入第{owner}章保留内容: {item}",
                            f"volume_{vol_index}.chapter_{chapter_number}",
                        ))
                must_not = set(self._as_list(chapter.get("must_not_write")))
                overlap = must_write.intersection(must_not)
                if overlap:
                    findings.append(self._finding(
                        "error",
                        "chapter_boundary_self_conflict",
                        f"第{chapter_number}章 must_write 与 must_not_write 冲突: {','.join(overlap)}",
                        f"volume_{vol_index}.chapter_{chapter_number}",
                    ))
                for target_chapter, item in self._reserved_items(chapter):
                    if target_chapter > chapter_number:
                        future_reserved[item] = target_chapter
        return findings

    def _score(self, errors: List[Dict[str, Any]], warnings: List[Dict[str, Any]], metrics: Dict[str, Any]) -> int:
        score = 100 - len(errors) * 15 - len(warnings) * 4
        total = metrics.get("total_chapters") or 0
        missing = metrics.get("missing_boundary_count") or 0
        if total:
            score -= int((missing / total) * 15)
        return max(0, min(100, score))

    def _combined_score(self, rule_score: int, semantic_quality: Dict[str, Any]) -> int:
        semantic_score = semantic_quality.get("score")
        if semantic_score is None or semantic_quality.get("status") == "unavailable":
            return rule_score
        semantic_score = self._clamp_int(semantic_score, 0, 100, default=rule_score)
        return self._clamp_int(rule_score * 0.65 + semantic_score * 0.35, 0, 100, default=rule_score)

    def _summary(
            self,
            status: str,
            score: int,
            errors: List[Dict[str, Any]],
            warnings: List[Dict[str, Any]],
            semantic_quality: Dict[str, Any]) -> str:
        semantic_score = semantic_quality.get("score")
        semantic_suffix = ""
        if semantic_quality.get("attempted") and semantic_quality.get("status") != "unavailable":
            semantic_suffix = f"，LLM语义评分 {semantic_score}"
        elif semantic_quality.get("status") == "unavailable":
            semantic_suffix = "，LLM语义评估不可用"
        if status == "passed":
            return f"大纲审查通过，综合评分 {score}{semantic_suffix}。"
        return f"大纲需要修改，综合评分 {score}{semantic_suffix}，错误 {len(errors)} 个，警告 {len(warnings)} 个。"

    def _write_report(self, project_id: str, book_id: str, report: Dict[str, Any]) -> Path:
        timestamp = datetime.now().strftime("%Y%m%d%H%M%S%f")
        report_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "novel" / "reviews" / book_id / "outline"
        )
        report_dir.mkdir(parents=True, exist_ok=True)
        report_path = report_dir / f"outline_review_{timestamp}.json"
        with report_path.open("w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
        return report_path

    def _write_fix_report(
            self,
            project_id: str,
            book_id: str,
            outline_path: Path,
            snapshot_path: Path,
            completion: Dict[str, Any]) -> Path:
        timestamp = datetime.now().strftime("%Y%m%d%H%M%S%f")
        report_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "novel" / "reviews" / book_id / "outline"
        )
        report_dir.mkdir(parents=True, exist_ok=True)
        report_path = report_dir / f"outline_boundary_fix_{timestamp}.json"
        report = {
            "review_type": "outline_boundary_fix",
            "project_id": project_id,
            "book_id": book_id,
            "outline_path": self._relative(project_id, outline_path),
            "snapshot_path": self._relative(project_id, snapshot_path),
            "completion": completion,
            "created_at": datetime.now().isoformat(),
        }
        with report_path.open("w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
        return report_path

    def _archive_outline(
            self,
            project_id: str,
            book_id: str,
            outline_path: Path,
            outline: Dict[str, Any]) -> Path:
        timestamp = datetime.now().strftime("%Y%m%d%H%M%S%f")
        versions_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "novel" / "outline" / "versions" / book_id
        )
        versions_dir.mkdir(parents=True, exist_ok=True)
        snapshot_path = versions_dir / f"{book_id}_outline_before_boundary_fix_{timestamp}.json"
        snapshot = dict(outline)
        snapshot["archived_at"] = datetime.now().isoformat()
        snapshot["archive_reason"] = "before_outline_boundary_fix"
        snapshot["source_path"] = self._relative(project_id, outline_path)
        with snapshot_path.open("w", encoding="utf-8") as handle:
            json.dump(snapshot, handle, ensure_ascii=False, indent=2, default=str)
        return snapshot_path

    def _relative(self, project_id: str, path: Path) -> str:
        root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        try:
            return str(path.relative_to(root)).replace("\\", "/")
        except ValueError:
            return str(path)

    def _book_id_from_path(self, path: Path) -> str:
        name = path.name
        return name[:-len("_outline.json")] if name.endswith("_outline.json") else path.stem

    def _finding(self, severity: str, code: str, message: str, path: str) -> Dict[str, Any]:
        return {
            "severity": severity,
            "code": code,
            "message": message,
            "path": path,
        }

    def _bool_option(
            self,
            request: AgentRequest,
            snake_key: str,
            camel_key: str,
            default: bool = False) -> bool:
        for source in (request.parameters, request.config, request.input_refs):
            value = source.get(snake_key)
            if value is None:
                value = source.get(camel_key)
            if value is not None:
                if isinstance(value, bool):
                    return value
                return str(value).lower() in {"true", "1", "yes", "y"}
        return default

    def _clamp_int(self, value: Any, minimum: int, maximum: int, default: int = 0) -> int:
        try:
            parsed = int(round(float(value)))
        except (TypeError, ValueError):
            parsed = default
        return max(minimum, min(maximum, parsed))

    def _is_empty(self, value: Any) -> bool:
        if value is None:
            return True
        if isinstance(value, str):
            return not value.strip()
        if isinstance(value, (list, dict)):
            return len(value) == 0
        return False

    def _as_list(self, value: Any) -> List[str]:
        if value is None:
            return []
        if isinstance(value, list):
            return [str(item).strip() for item in value if str(item).strip()]
        if isinstance(value, dict):
            return [str(item).strip() for item in value.values() if str(item).strip()]
        if isinstance(value, str):
            return [item.strip() for item in value.replace("；", ";").replace("，", ",").split(",") if item.strip()]
        return [str(value)]

    def _reserved_items(self, chapter: Dict[str, Any]) -> List[Tuple[int, str]]:
        value = chapter.get("reserved_for_future")
        if not isinstance(value, dict):
            return []
        items: List[Tuple[int, str]] = []
        for raw_key, raw_value in value.items():
            text = str(raw_value).strip()
            if not text:
                continue
            digits = "".join(ch for ch in str(raw_key) if ch.isdigit())
            if not digits:
                continue
            items.append((int(digits), text))
        return items
