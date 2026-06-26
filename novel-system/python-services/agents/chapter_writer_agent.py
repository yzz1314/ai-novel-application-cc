"""
章节创作Agent
基于章节大纲、项目Skills和前文摘要生成章节正文
"""
import json
import uuid
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List, Optional

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from schemas.chapter_schemas import (
    ChapterWriteRequest,
    ChapterContent,
    ChapterReview,
    ChapterWriteResponse
)
from schemas.outline_schemas import BookOutlineSchema, ChapterOutlineSchema
from llm.client import LLMClient
from quality import BoundaryChecker
from retrieval.context_builder import ContextBuilder
from skills.skill_loader import SkillLoader
from config import settings
from utils.logger import get_logger


class ChapterWriterAgent(BaseAgent):
    """章节创作Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("ChapterWriterAgent")
        self.supported_tasks = ["chapter_writing"]
        self.llm_client = llm_client or LLMClient()
        self.skill_loader = SkillLoader()
        self.boundary_checker = BoundaryChecker()
        self.logger = get_logger("ChapterWriterAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行章节创作任务

        流程：
        1. 加载章节大纲
        2. 加载项目Skills
        3. 加载前文上下文
        4. 生成章节正文
        5. 审查（可选）
        6. 保存
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)

            # 2. 解析创作请求
            write_request = ChapterWriteRequest(**request.parameters)
            self.logger.info(
                f"Writing chapter {write_request.volume_number}-{write_request.chapter_number} "
                f"for book {write_request.book_id}"
            )

            # 3. 加载大纲
            outline = await self._load_outline(write_request.project_id, write_request.book_id)
            chapter_outline = await self._get_chapter_outline(
                outline, write_request.volume_number, write_request.chapter_number
            )
            future_chapters = self._get_future_chapters(
                outline, write_request.volume_number, write_request.chapter_number
            )
            boundary_control = self.boundary_checker.build_boundary_control(chapter_outline, future_chapters)
            future_protection = self.boundary_checker.build_future_protection(
                write_request.chapter_number, future_chapters
            )

            # 覆盖配置
            if write_request.chapter_title:
                chapter_outline.chapter_title = write_request.chapter_title
            if write_request.target_word_count:
                chapter_outline.target_word_count = write_request.target_word_count

            # 4. 加载Skills
            skills_content = await self._load_skills(
                write_request.project_id, write_request.use_project_skills
            )

            # 5. 加载前文上下文
            previous_context = await self._load_previous_context(
                write_request, outline
            ) if write_request.use_previous_context else ""

            # 6. 构建检索上下文包
            retrieval_context = await ContextBuilder(write_request.project_id).build_chapter_context(
                book_id=write_request.book_id,
                volume_number=write_request.volume_number,
                chapter_number=write_request.chapter_number,
                chapter_outline=chapter_outline,
                previous_context=previous_context,
            )
            skills_content["__retrieval_context__"] = retrieval_context.get("prompt_section", "")
            skills_content["__retrieval_context_path__"] = retrieval_context.get("path", "")
            skills_content["__boundary_prompt__"] = self.boundary_checker.prompt_section(
                boundary_control, future_protection
            )

            # 7. 生成章节正文
            self.logger.info("Generating chapter content...")
            chapter_content = await self._generate_chapter_content(
                write_request, chapter_outline, skills_content, previous_context, outline
            )

            # 8. 审查 + 边界检查 + 自动返修
            review_result = None
            max_review_iterations = max(1, write_request.review_iterations)
            revision_history: List[Dict[str, Any]] = []
            for iteration in range(max_review_iterations):
                if write_request.auto_review:
                    self.logger.info("Reviewing chapter...")
                    review_result = await self._review_chapter(
                        chapter_content, chapter_outline, skills_content
                    )

                boundary_result = self.boundary_checker.check(
                    chapter_content.content,
                    boundary_control,
                    future_protection,
                )
                chapter_content.boundary_check = boundary_result.to_dict()

                issues = self._revision_issues(review_result, boundary_result)
                if not issues:
                    break

                chapter_content.review_status = "needs_revision"
                chapter_content.review_comments = self._issue_messages(issues)
                if iteration >= max_review_iterations - 1:
                    break

                self.logger.info(
                    "Revising chapter based on %s issue(s), iteration %s/%s",
                    len(issues),
                    iteration + 1,
                    max_review_iterations - 1,
                )
                chapter_content = await self._revise_chapter(
                    chapter_content=chapter_content,
                    review_result=review_result,
                    chapter_outline=chapter_outline,
                    skills_content=skills_content,
                    issues=issues,
                    iteration=iteration + 1,
                )
                revision_history.append(chapter_content.revision_history[-1])

            boundary_result = self.boundary_checker.check(
                chapter_content.content,
                boundary_control,
                future_protection,
            )
            chapter_content.boundary_check = boundary_result.to_dict()
            if boundary_result.blocking_errors:
                chapter_content.review_status = "needs_revision"
                chapter_content.review_comments = self._issue_messages(boundary_result.blocking_errors)
            elif review_result and review_result.pass_review:
                chapter_content.review_status = "reviewed"
            chapter_content.revision_history = revision_history or chapter_content.revision_history

            # 9. 保存章节
            chapter_path = await self._save_chapter(write_request.project_id, chapter_content)

            # 10. 构建响应
            chapter_response = ChapterWriteResponse(
                chapter_id=chapter_content.chapter_id,
                chapter_content=chapter_content,
                review_result=review_result,
                generation_time_ms=int((datetime.now() - self.metrics["start_time"]).total_seconds() * 1000),
                llm_calls=self.metrics["llm_calls"],
                total_tokens=self.metrics["input_tokens"] + self.metrics["output_tokens"],
                warnings=[]
            )

            output_refs = [str(chapter_path)]

            structured_output = {
                "chapter_id": chapter_content.chapter_id,
                "chapter_title": chapter_content.chapter_title,
                "word_count": chapter_content.word_count,
                "quality_score": chapter_content.quality_score,
                "review_status": chapter_content.review_status,
                "chapter_path": str(chapter_path),
                "context_pack_path": retrieval_context.get("path", ""),
                "boundary_passed": boundary_result.passed,
                "boundary_blocking_errors": len(boundary_result.blocking_errors),
                "boundary_warnings": len(boundary_result.warnings),
                "revision_count": len(chapter_content.revision_history),
            }

            return self._build_response(
                request=request,
                status="success" if boundary_result.passed and chapter_content.review_status != "needs_revision" else "partial",
                output_refs=output_refs,
                structured_output=structured_output,
                warnings=boundary_result.warnings,
                errors=boundary_result.blocking_errors,
            )

        except Exception as e:
            self.logger.error(f"Chapter writing failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "CHAPTER_WRITING_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    async def _load_outline(self, project_id: str, book_id: str) -> BookOutlineSchema:
        """加载书籍大纲"""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        candidates = [
            project_root / "novel" / "outline" / f"{book_id}_outline.json",
            project_root / "outlines" / f"{book_id}_outline.json",
        ]

        outline_file = next((path for path in candidates if path.exists()), None)
        if not outline_file:
            raise FileNotFoundError(
                "Outline not found. Checked: " +
                ", ".join(str(path) for path in candidates)
            )

        with open(outline_file, 'r', encoding='utf-8') as f:
            outline_data = json.load(f)

        return BookOutlineSchema(**outline_data)

    async def _get_chapter_outline(self, outline: BookOutlineSchema,
                                   volume_number: int, chapter_number: int) -> ChapterOutlineSchema:
        """获取章节大纲"""
        # 找到对应的卷
        volume = next((v for v in outline.volumes if v.volume_number == volume_number), None)
        if not volume:
            raise ValueError(f"Volume {volume_number} not found")

        # 找到对应的章节
        chapter = next((ch for ch in volume.chapters if ch.chapter_number == chapter_number), None)
        if not chapter:
            raise ValueError(f"Chapter {chapter_number} not found in volume {volume_number}")

        return chapter

    def _get_future_chapters(
            self,
            outline: BookOutlineSchema,
            volume_number: int,
            chapter_number: int,
            limit: int = 5) -> List[ChapterOutlineSchema]:
        """获取后续章纲保护清单。"""
        volume = next((v for v in outline.volumes if v.volume_number == volume_number), None)
        if not volume:
            return []
        return [
            chapter
            for chapter in volume.chapters
            if chapter.chapter_number > chapter_number
        ][:limit]

    async def _load_skills(self, project_id: str, use_project_skills: bool) -> Dict[str, str]:
        """加载项目Skills"""
        skills_content = {}

        if use_project_skills:
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
                    self.logger.info(f"Loaded skill: {skill_name}")

        project_soul = await self._load_project_soul(project_id)
        if project_soul:
            skills_content["__project_soul__"] = project_soul

        return skills_content

    async def _load_project_soul(self, project_id: str) -> str:
        """加载Project Soul。"""
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

    def _retrieval_context_prompt_section(self, skills_content: Dict[str, str]) -> str:
        """构建检索上下文 prompt 注入片段。"""
        retrieval_context = skills_content.get("__retrieval_context__", "").strip()
        if not retrieval_context:
            return ""
        return f"""
{retrieval_context}
"""

    def _boundary_prompt_section(self, skills_content: Dict[str, str]) -> str:
        """构建章节边界 prompt 注入片段。"""
        boundary_prompt = skills_content.get("__boundary_prompt__", "").strip()
        if not boundary_prompt:
            return ""
        return f"""
{boundary_prompt}
"""

    async def _load_previous_context(self, write_request: ChapterWriteRequest,
                                     outline: BookOutlineSchema) -> str:
        """加载前文上下文"""
        context_parts = []

        # 确定要加载的章节范围
        start_chapter = max(1, write_request.chapter_number - write_request.context_chapters)
        end_chapter = write_request.chapter_number - 1

        if start_chapter > end_chapter:
            return ""

        # 加载前面几章的摘要或正文
        candidate_dirs = [
            Path(settings.PROJECT_BASE_PATH) / "projects" / write_request.project_id /
            "novel" / "chapters" / "drafts" / write_request.book_id /
            f"volume_{write_request.volume_number}",
            Path(settings.PROJECT_BASE_PATH) / "projects" / write_request.project_id /
            "books" / write_request.book_id / f"volume_{write_request.volume_number}" / "chapters",
        ]

        for ch_num in range(start_chapter, end_chapter + 1):
            chapter_file = next(
                (
                    chapter_dir / f"chapter_{ch_num}.json"
                    for chapter_dir in candidate_dirs
                    if (chapter_dir / f"chapter_{ch_num}.json").exists()
                ),
                None
            )
            if chapter_file:
                with open(chapter_file, 'r', encoding='utf-8') as f:
                    chapter_data = json.load(f)
                    # 只取摘要或前300字
                    content = chapter_data.get("content", "")
                    summary = content[:300] + "..." if len(content) > 300 else content
                    context_parts.append(f"第{ch_num}章摘要：{summary}")

        return "\n\n".join(context_parts)

    async def _generate_chapter_content(self, write_request: ChapterWriteRequest,
                                       chapter_outline: ChapterOutlineSchema,
                                       skills_content: Dict[str, str],
                                       previous_context: str,
                                       outline: BookOutlineSchema) -> ChapterContent:
        """生成章节正文"""
        # 构建prompt
        prompt = self._build_chapter_generation_prompt(
            write_request, chapter_outline, skills_content, previous_context, outline
        )

        # 调用LLM生成
        llm_response = await self.llm_client.generate_with_retry(
            prompt=prompt,
            response_format="text",  # 正文生成用text格式
            max_retries=3,
            max_tokens=6000,  # 章节较长，需要更多tokens
            temperature=write_request.creativity_level
        )

        # 更新指标
        self.metrics["llm_calls"] += 1
        self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
        self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

        # 提取正文
        content = llm_response["content"].strip()
        word_count = len(content)

        # 构建ChapterContent
        chapter_id = f"chapter_{uuid.uuid4().hex[:8]}"
        chapter_content = ChapterContent(
            chapter_id=chapter_id,
            book_id=write_request.book_id,
            volume_number=write_request.volume_number,
            chapter_number=write_request.chapter_number,
            chapter_title=chapter_outline.chapter_title,
            content=content,
            word_count=word_count,
            based_on_outline=True,
            outline_summary=chapter_outline.plot_goal,
            used_skills=[
                skill_name
                for skill_name in skills_content.keys()
                if not skill_name.startswith("__")
            ],
            referenced_chapters=list(range(
                max(1, write_request.chapter_number - write_request.context_chapters),
                write_request.chapter_number
            )) if write_request.use_previous_context else []
        )

        self.logger.info(f"Generated chapter with {word_count} characters")
        return chapter_content

    def _build_chapter_generation_prompt(self, write_request: ChapterWriteRequest,
                                        chapter_outline: ChapterOutlineSchema,
                                        skills_content: Dict[str, str],
                                        previous_context: str,
                                        outline: BookOutlineSchema) -> str:
        """构建章节生成prompt"""

        # 提取writing_skill中的关键指导（简化版）
        writing_guidance = ""
        if "writing_skill" in skills_content:
            writing_guidance = "请参考项目的正文创作Skill中的文笔特点、场景技巧和创作要点。"
        project_soul_section = self._project_soul_prompt_section(skills_content)
        retrieval_context_section = self._retrieval_context_prompt_section(skills_content)
        boundary_section = self._boundary_prompt_section(skills_content)

        # 构建人物信息
        characters_info = "\n".join([
            f"- {ch.name}（{ch.role}）：{ch.description[:100]}"
            for ch in outline.characters[:5]
        ])

        previous_context_section = (
            f"## 前文回顾\n\n{previous_context}" if previous_context else ""
        )

        prompt = f"""
你是一位专业的网络小说作家。请根据以下大纲和指导创作章节正文。

{project_soul_section}

## 书籍信息

- 书名：{outline.book_title}
- 类型：{outline.genre}
- 核心概念：{outline.core_concept}

## 主要人物

{characters_info}

## 本章大纲

- 章节：第{write_request.volume_number}卷 第{write_request.chapter_number}章
- 标题：{chapter_outline.chapter_title}
- 目标字数：{chapter_outline.target_word_count}字

### 本章目标
- 剧情推进：{chapter_outline.plot_goal}
- 人物发展：{chapter_outline.character_development}
- 信息揭示：{chapter_outline.info_reveal}

### 关键要素
- 冲突：{chapter_outline.conflict}
- 爽点设计：{chapter_outline.appeal_point}
- 悬念/伏笔：{chapter_outline.suspense}

### 承接关系
- 承接上章：{chapter_outline.connect_previous}
- 引出下章：{chapter_outline.lead_to_next}

### 节奏和密度
- 节奏类型：{chapter_outline.pace_type}
- 信息密度：{chapter_outline.info_density}

{previous_context_section}

{retrieval_context_section}

{boundary_section}

{writing_guidance}

## 创作要求

1. **字数**：严格控制在{chapter_outline.target_word_count}字左右（±200字）
2. **结构**：
   - 开头：{chapter_outline.connect_previous}
   - 中段：推进剧情，展现冲突和爽点
   - 结尾：{chapter_outline.lead_to_next}
3. **节奏**：保持{chapter_outline.pace_type}节奏
4. **风格**：符合{outline.genre}类型的风格特点
5. **避免**：
   - 不要写章节标题
   - 不要写"第X章"等标记
   - 不要写作者后记或说明
   - 直接开始正文内容
6. **边界**：只写当前章，不得提前解决后续章节冲突、揭晓后续真相或完成后续章纲

## 输出要求

请直接输出章节正文，不要有任何前言、后记或说明。
"""
        return prompt

    async def _review_chapter(self, chapter_content: ChapterContent,
                             chapter_outline: ChapterOutlineSchema,
                             skills_content: Dict[str, str]) -> ChapterReview:
        """审查章节"""
        # 构建审查prompt
        prompt = self._build_review_prompt(chapter_content, chapter_outline, skills_content)

        # 调用LLM审查
        llm_response = await self.llm_client.generate_with_retry(
            prompt=prompt,
            response_format="json",
            max_retries=3,
            max_tokens=2000
        )

        # 更新指标
        self.metrics["llm_calls"] += 1
        self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
        self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

        # 解析审查结果
        review_data = json.loads(llm_response["content"])

        # 构建ChapterReview
        review = ChapterReview(
            chapter_id=chapter_content.chapter_id,
            style_consistency=review_data.get("style_consistency", 7.0),
            technique_usage=review_data.get("technique_usage", 7.0),
            quality_level=review_data.get("quality_level", 7.0),
            continuity=review_data.get("continuity", 7.0),
            structure=review_data.get("structure", 7.0),
            total_score=review_data.get("total_score", 35.0),
            overall_rating=review_data.get("overall_rating", "good"),
            issues=review_data.get("issues", []),
            suggestions=review_data.get("suggestions", []),
            strengths=review_data.get("strengths", []),
            pass_review=review_data.get("pass_review", True),
            needs_revision=review_data.get("needs_revision", False)
        )

        # 更新章节内容的质量信息
        chapter_content.quality_score = review.total_score * 2  # 转换为0-100分
        chapter_content.review_status = "reviewed" if review.pass_review else "needs_revision"
        chapter_content.review_comments = review.suggestions

        return review

    def _build_review_prompt(self, chapter_content: ChapterContent,
                            chapter_outline: ChapterOutlineSchema,
                            skills_content: Dict[str, str]) -> str:
        """构建审查prompt"""

        # 提取review_skill中的检查点（简化版）
        review_guidance = ""
        if "review_skill" in skills_content:
            review_guidance = "参考项目的审查Skill进行检查。"
        project_soul_section = self._project_soul_prompt_section(skills_content)
        boundary_section = self._boundary_prompt_section(skills_content)

        prompt = f"""
你是一位专业的小说编辑。请审查以下章节内容。

{project_soul_section}

## 章节信息

- 标题：{chapter_content.chapter_title}
- 字数：{chapter_content.word_count}字
- 目标字数：{chapter_outline.target_word_count}字

## 大纲要求

- 剧情目标：{chapter_outline.plot_goal}
- 冲突：{chapter_outline.conflict}
- 爽点：{chapter_outline.appeal_point}

{boundary_section}

## 章节正文（前500字）

{chapter_content.content[:500]}...

{review_guidance}

## 审查维度

请从以下5个维度评分（每项0-10分）：

1. **风格一致性**：文笔风格是否统一
2. **技巧运用**：是否有效运用写作技巧
3. **质量水平**：整体文笔质量
4. **连续性**：与前文的连贯性
5. **结构完整性**：章节结构是否完整
6. **章节边界**：是否只完成本章目标，是否提前侵占后续章纲

## 输出格式

请以JSON格式返回：

```json
{{
  "style_consistency": 8.0,
  "technique_usage": 7.5,
  "quality_level": 8.5,
  "continuity": 8.0,
  "structure": 9.0,
  "total_score": 41.0,
  "overall_rating": "good",
  "issues": [
    {{"severity": "minor", "description": "问题描述", "location": "位置"}}
  ],
  "suggestions": ["建议1", "建议2"],
  "strengths": ["优点1", "优点2"],
  "pass_review": true,
  "needs_revision": false
}}
```

注意：
- total_score是5项评分之和
- overall_rating: excellent(45-50), good(40-44), pass(35-39), fail(<35)
- pass_review: total_score >= 35
- needs_revision: 存在中等或严重问题
"""
        return prompt

    def _revision_issues(self, review_result: Optional[ChapterReview], boundary_result) -> List[Dict[str, Any]]:
        """汇总需要返修的问题。"""
        issues: List[Dict[str, Any]] = []
        if review_result:
            for issue in review_result.issues:
                severity = str(issue.get("severity", "warning")).lower()
                if severity in {"error", "critical", "blocking", "major"}:
                    issues.append({
                        "severity": severity,
                        "type": issue.get("type", "review_issue"),
                        "message": issue.get("description") or issue.get("message") or "审查发现阻塞问题",
                        "evidence": issue.get("location", ""),
                        "suggestion": issue.get("suggestion", ""),
                    })
            if review_result.needs_revision and not issues:
                issues.extend([
                    {
                        "severity": "warning",
                        "type": "review_suggestion",
                        "message": suggestion,
                        "evidence": "",
                        "suggestion": suggestion,
                    }
                    for suggestion in review_result.suggestions[:3]
                ])
        issues.extend(boundary_result.blocking_errors)
        return issues

    def _issue_messages(self, issues: List[Dict[str, Any]]) -> List[str]:
        return [str(issue.get("message", issue)) for issue in issues if issue]

    async def _revise_chapter(
            self,
            chapter_content: ChapterContent,
            review_result: Optional[ChapterReview],
            chapter_outline: ChapterOutlineSchema,
            skills_content: Dict[str, str],
            issues: List[Dict[str, Any]],
            iteration: int) -> ChapterContent:
        """根据审查和边界问题返修章节。"""
        original_content = chapter_content.content
        prompt = self._build_revision_prompt(
            chapter_content=chapter_content,
            chapter_outline=chapter_outline,
            skills_content=skills_content,
            issues=issues,
            iteration=iteration,
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

        revised_content = llm_response["content"].strip()
        if not revised_content:
            revised_content = original_content

        chapter_content.content = revised_content
        chapter_content.word_count = len(revised_content)
        chapter_content.version += 1
        chapter_content.updated_at = datetime.now()
        chapter_content.revision_history.append({
            "iteration": iteration,
            "revised_at": datetime.now().isoformat(),
            "trigger_types": sorted({str(issue.get("type", "unknown")) for issue in issues}),
            "issues": issues,
            "review_score_before": review_result.total_score if review_result else None,
            "word_count_before": len(original_content),
            "word_count_after": len(revised_content),
            "status": "revised",
        })

        return chapter_content

    def _build_revision_prompt(
            self,
            chapter_content: ChapterContent,
            chapter_outline: ChapterOutlineSchema,
            skills_content: Dict[str, str],
            issues: List[Dict[str, Any]],
            iteration: int) -> str:
        issue_lines = "\n".join([
            f"- [{issue.get('severity', 'warning')}] {issue.get('type', '')}: {issue.get('message', '')}\n  建议：{issue.get('suggestion', '')}\n  证据：{issue.get('evidence', '')}"
            for issue in issues
        ])
        project_soul_section = self._project_soul_prompt_section(skills_content)
        boundary_section = self._boundary_prompt_section(skills_content)

        return f"""
你是一位严格执行大纲和边界控制的小说改稿编辑。请对章节正文进行第{iteration}轮返修。

{project_soul_section}

## 当前章节

- 标题：{chapter_content.chapter_title}
- 章节：第{chapter_content.volume_number}卷 第{chapter_content.chapter_number}章
- 当前字数：{chapter_content.word_count}

## 章节大纲

- 剧情目标：{chapter_outline.plot_goal}
- 人物发展：{chapter_outline.character_development}
- 信息揭示：{chapter_outline.info_reveal}
- 冲突：{chapter_outline.conflict}
- 章末牵引：{chapter_outline.lead_to_next}

{boundary_section}

## 必须修复的问题

{issue_lines}

## 原正文

{chapter_content.content}

## 返修要求

1. 必须修复上方列出的 blocking/error 问题。
2. 不得写入 must_not_write，不得提前完成后续章纲保护内容。
3. 保留本章已有效的情节、语气和连续性。
4. 直接输出返修后的完整章节正文，不要解释修改过程，不要输出标题。
"""

    async def _save_chapter(self, project_id: str, chapter_content: ChapterContent) -> Path:
        """保存章节"""
        # 确定保存路径
        chapters_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" /
            project_id / "novel" / "chapters" / "drafts" /
            chapter_content.book_id / f"volume_{chapter_content.volume_number}"
        )
        chapters_dir.mkdir(parents=True, exist_ok=True)

        chapter_file = chapters_dir / f"chapter_{chapter_content.chapter_number}.json"

        # 保存为JSON
        chapter_dict = chapter_content.dict()
        with open(chapter_file, 'w', encoding='utf-8') as f:
            json.dump(chapter_dict, f, ensure_ascii=False, indent=2, default=str)

        # 同时保存纯文本版本
        text_file = chapters_dir / f"chapter_{chapter_content.chapter_number}.txt"
        with open(text_file, 'w', encoding='utf-8') as f:
            f.write(f"{chapter_content.chapter_title}\n\n")
            f.write(chapter_content.content)

        self.logger.info(f"Saved chapter to {chapter_file}")
        return chapter_file
