"""
单书汇总Agent
基于逐块分析结果生成单书分析报告
"""
import json
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from llm.client import LLMClient
from llm.prompt_builder import PromptBuilder
from config import settings
from utils.logger import get_logger


class BookSummaryAgent(BaseAgent):
    """单书汇总Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("BookSummaryAgent")
        self.supported_tasks = ["book_summary"]
        self.llm_client = llm_client or LLMClient()
        self.prompt_builder = PromptBuilder()
        self.logger = get_logger("BookSummaryAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行单书汇总任务

        流程：
        1. 读取分析摘要
        2. 读取所有chunk分析结果
        3. 构建汇总prompt
        4. 调用LLM生成报告
        5. 保存Markdown报告
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)

            project_id = request.project_id
            sample_id = request.input_refs.get("sample_id") or request.parameters.get("sample_id")

            self.logger.info(f"Generating summary for sample: {sample_id}")

            # 2. 读取分析摘要
            analysis_summary = await self._read_analysis_summary(project_id, sample_id)

            # 3. 读取所有chunk分析结果
            analysis_results = await self._read_analysis_results(project_id, sample_id)
            self.logger.info(f"Loaded {len(analysis_results)} analysis results")

            # 4. 构建书籍信息
            book_info = {
                "title": analysis_summary.get("title", "未知"),
                "total_chars": analysis_summary.get("total_chars", 0),
                "total_chapters": analysis_summary.get("total_chapters", 0)
            }

            # 5. 构建prompt
            prompt = self.prompt_builder.build_book_summary_prompt(
                analysis_results=analysis_results,
                book_info=book_info
            )

            # 6. 调用LLM生成报告
            self.logger.info("Calling LLM to generate book summary report...")
            llm_warning = None
            try:
                llm_response = await self.llm_client.generate_with_retry(
                    prompt=prompt,
                    response_format="text",
                    max_retries=int(request.parameters.get("llm_max_retries", 1)),
                    max_tokens=int(request.parameters.get("max_tokens", 1600)),
                    timeout=float(request.parameters.get("timeout", 60)),
                    num_retries=int(request.parameters.get("num_retries", 0)),
                    sdk_max_retries=int(request.parameters.get("sdk_max_retries", 0)),
                    cache_enabled=bool(request.parameters.get("cache_enabled", False))
                )

                # 更新指标
                self.metrics["llm_calls"] += 1
                self.metrics["input_tokens"] += llm_response["usage"]["prompt_tokens"]
                self.metrics["output_tokens"] += llm_response["usage"]["completion_tokens"]

                report_content = llm_response["content"]
                response_status = "success"
                warnings = []
            except Exception as llm_error:
                error_message = str(llm_error)
                if not self._is_llm_timeout(error_message):
                    raise

                self.logger.warning(
                    "Book summary LLM timed out; writing local partial report for %s: %s",
                    sample_id,
                    error_message
                )
                report_content = self._build_partial_report(
                    book_info=book_info,
                    analysis_summary=analysis_summary,
                    analysis_results=analysis_results,
                    error_message=error_message
                )
                response_status = "partial"
                llm_warning = {
                    "code": "BOOK_SUMMARY_LLM_TIMEOUT_FALLBACK",
                    "message": "真实模型生成单书报告超时，已基于现有分块分析生成本地草稿报告。",
                    "detail": error_message,
                    "retryable": True
                }
                warnings = [llm_warning]

            # 7. 保存Markdown报告
            report_path = await self._save_report(
                project_id, sample_id, report_content
            )

            # 8. 提取关键信息作为结构化输出
            structured_output = {
                "sample_id": sample_id,
                "title": book_info["title"],
                "report_length": len(report_content),
                "technique_stats": analysis_summary.get("technique_stats", {}),
                "partial": response_status == "partial",
                "created_at": datetime.now().isoformat()
            }
            if llm_warning:
                structured_output["fallback_reason"] = llm_warning["code"]

            # 9. 构建响应
            output_refs = [
                f"analysis/per_book/{sample_id}_report.md"
            ]

            return self._build_response(
                request=request,
                status=response_status,
                output_refs=output_refs,
                structured_output=structured_output,
                warnings=warnings
            )

        except Exception as e:
            self.logger.error(f"Book summary failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "SUMMARY_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    def _is_llm_timeout(self, message: str) -> bool:
        lowered = (message or "").lower()
        return "timeout" in lowered or "timed out" in lowered or "超时" in lowered

    def _build_partial_report(
            self,
            book_info: Dict,
            analysis_summary: Dict,
            analysis_results: List[Dict],
            error_message: str) -> str:
        summaries = []
        for index, result in enumerate(analysis_results[:20], 1):
            analysis = result.get("analysis") if isinstance(result, dict) else {}
            summary = result.get("summary") if isinstance(result, dict) else None
            if not summary and isinstance(analysis, dict):
                summary = analysis.get("summary")
            if summary:
                summaries.append(f"{index}. {summary}")

        technique_stats = analysis_summary.get("technique_stats") or {}
        technique_lines = []
        if isinstance(technique_stats, dict) and technique_stats:
            for name, count in list(technique_stats.items())[:20]:
                technique_lines.append(f"- {name}: {count}")
        if not technique_lines:
            technique_lines.append("- 暂无聚合技巧统计。")

        summary_block = "\n".join(summaries) if summaries else "暂无可用分块摘要。"
        technique_block = "\n".join(technique_lines)
        created_at = datetime.now().isoformat()

        return f"""# 《{book_info.get('title', '作品')}》分析报告（本地草稿）

> 真实模型生成超时，系统已基于已完成的分块分析生成这份本地草稿。原始错误：{error_message}

## 一、整体概况
- 书名：{book_info.get('title', '未知')}
- 总字数：{book_info.get('total_chars', 0)}
- 总章节数：{book_info.get('total_chapters', 0)}
- 已分析块数：{len(analysis_results)}
- 生成时间：{created_at}

## 二、分块摘要摘录
{summary_block}

## 三、技巧统计
{technique_block}

## 四、后续建议
- 自定义模型端点恢复稳定后，可重新点击“重试”生成完整 LLM 报告。
- 当前草稿仅汇总现有分块分析，不替代模型生成的深度风格归纳。
"""

    async def _read_analysis_summary(self, project_id: str, sample_id: str) -> Dict:
        """读取分析摘要"""
        summary_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_chunk" / sample_id / "summary.json"
        )
        legacy_summary_file = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "analysis" / sample_id / "summary.json"
        )

        if not summary_file.exists() and legacy_summary_file.exists():
            summary_file = legacy_summary_file

        if not summary_file.exists():
            raise FileNotFoundError(f"Analysis summary not found: {summary_file}")

        with open(summary_file, 'r', encoding='utf-8') as f:
            return json.load(f)

    async def _read_analysis_results(self, project_id: str, sample_id: str) -> List[Dict]:
        """读取所有chunk分析结果"""
        analysis_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_chunk" / sample_id
        )
        legacy_analysis_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "analysis" / sample_id
        )

        if not analysis_dir.exists() and legacy_analysis_dir.exists():
            analysis_dir = legacy_analysis_dir

        if not analysis_dir.exists():
            raise FileNotFoundError(f"Analysis directory not found: {analysis_dir}")

        results = []
        for analysis_file in sorted(analysis_dir.glob("*_analysis.json")):
            with open(analysis_file, 'r', encoding='utf-8') as f:
                results.append(json.load(f))

        return results

    async def _save_report(self, project_id: str, sample_id: str,
                          report_content: str) -> Path:
        """保存Markdown报告"""
        reports_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "analysis" / "per_book"
        )
        reports_dir.mkdir(parents=True, exist_ok=True)

        report_file = reports_dir / f"{sample_id}_report.md"
        with open(report_file, 'w', encoding='utf-8') as f:
            f.write(report_content)

        legacy_reports_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "reports"
        )
        legacy_reports_dir.mkdir(parents=True, exist_ok=True)
        with open(legacy_reports_dir / f"{sample_id}_report.md", 'w', encoding='utf-8') as f:
            f.write(report_content)

        self.logger.info(f"Saved report to {report_file}")
        return report_file
