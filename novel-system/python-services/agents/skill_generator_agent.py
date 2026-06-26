"""
Skill生成Agent
基于跨书归纳结果自动生成项目专属Skills
"""
import json
import shutil
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List

import yaml

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from llm.client import LLMClient
from config import settings
from utils.logger import get_logger


class SkillGeneratorAgent(BaseAgent):
    """Skill生成Agent"""

    def __init__(self, llm_client: LLMClient = None):
        super().__init__("SkillGeneratorAgent")
        self.supported_tasks = ["skill_generation"]
        self.llm_client = llm_client or LLMClient()
        self.logger = get_logger("SkillGeneratorAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """
        执行Skill生成任务

        流程：
        1. 读取跨书归纳结果
        2. 读取技巧汇总
        3. 加载Skill模板
        4. 为每个模板生成填充数据
        5. 生成三个Skill文件
        6. 保存和验证
        """
        self.metrics["start_time"] = datetime.now()

        try:
            # 1. 验证请求
            await self.validate_request(request)

            project_id = request.project_id
            skill_types = request.parameters.get("skill_types", ["writing", "outline", "review"])

            self.logger.info(f"Generating skills for project: {project_id}")
            self.logger.info(f"Skill types: {skill_types}")

            # 2. 读取跨书归纳结果
            synthesis_report = await self._read_synthesis_report(project_id)
            technique_summary = await self._read_technique_summary(project_id)

            # 3. 读取样本信息
            sample_info = await self._read_sample_info(project_id)

            # 4. 为每种类型生成Skill
            generated_skills = []

            for skill_type in skill_types:
                self.logger.info(f"Generating {skill_type} skill...")

                # 加载模板
                template = await self._load_template(skill_type)

                # 生成填充数据
                fill_data = await self._generate_fill_data(
                    skill_type=skill_type,
                    project_id=project_id,
                    synthesis_report=synthesis_report,
                    technique_summary=technique_summary,
                    sample_info=sample_info
                )

                # 填充模板
                skill_content = await self._fill_template(template, fill_data)

                # 保存Skill
                skill_path = await self._save_skill(
                    project_id=project_id,
                    skill_type=skill_type,
                    skill_content=skill_content
                )

                generated_skills.append({
                    "skill_type": skill_type,
                    "skill_name": f"{skill_type}_skill",
                    "file_path": str(skill_path),
                    "relative_path": str(skill_path.relative_to(
                        Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
                    )).replace("\\", "/"),
                    "enabled": True
                })

                self.logger.info(f"{skill_type} skill generated: {skill_path}")

            enabled_yaml_path = await self._write_enabled_yaml(project_id, generated_skills)

            # 5. 构建响应
            output_refs = [skill["file_path"] for skill in generated_skills]
            output_refs.append(str(enabled_yaml_path))

            structured_output = {
                "project_id": project_id,
                "generated_skills": generated_skills,
                "enabled_yaml": str(enabled_yaml_path),
                "skill_count": len(generated_skills),
                "sample_count": len(sample_info.get("samples", [])),
                "created_at": datetime.now().isoformat()
            }

            return self._build_response(
                request=request,
                status="success",
                output_refs=output_refs,
                structured_output=structured_output
            )

        except Exception as e:
            self.logger.error(f"Skill generation failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{
                    "code": "SKILL_GENERATION_ERROR",
                    "message": str(e),
                    "retryable": True
                }]
            )

    async def _read_synthesis_report(self, project_id: str) -> str:
        """读取跨书归纳报告"""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        candidates = [
            project_root / "analysis" / "cross_book" / "cross_book_synthesis.md",
            project_root / "cross_book_synthesis.md",
        ]

        report_file = next((path for path in candidates if path.exists()), None)
        if not report_file:
            raise FileNotFoundError(
                "Cross-book synthesis report not found. Checked: " +
                ", ".join(str(path) for path in candidates)
            )

        with open(report_file, 'r', encoding='utf-8') as f:
            return f.read()

    async def _read_technique_summary(self, project_id: str) -> Dict:
        """读取技巧汇总"""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        candidates = [
            project_root / "analysis" / "cross_book" / "technique_summary.json",
            project_root / "technique_summary.json",
        ]

        summary_file = next((path for path in candidates if path.exists()), None)
        if not summary_file:
            raise FileNotFoundError(
                "Technique summary not found. Checked: " +
                ", ".join(str(path) for path in candidates)
            )

        with open(summary_file, 'r', encoding='utf-8') as f:
            return json.load(f)

    async def _read_sample_info(self, project_id: str) -> Dict:
        """读取样本信息"""
        # 读取所有样本的manifest
        manifests_dir = (
            Path(settings.PROJECT_BASE_PATH) / "projects" / project_id /
            "samples" / "manifests"
        )

        if not manifests_dir.exists():
            return {"samples": []}

        samples = []
        for manifest_file in manifests_dir.glob("*_manifest.json"):
            with open(manifest_file, 'r', encoding='utf-8') as f:
                manifest = json.load(f)
                samples.append({
                    "sample_id": manifest.get("sample_id"),
                    "title": manifest.get("title", "未知"),
                    "total_chars": manifest.get("total_chars", 0),
                    "total_chapters": manifest.get("total_chapters", 0),
                    "total_chunks": manifest.get("total_chunks", 0)
                })

        return {"samples": samples}

    async def _load_template(self, skill_type: str) -> str:
        """加载Skill模板"""
        template_file = (
            Path(__file__).parent.parent / "skills" / "templates" /
            f"{skill_type}_skill_template.md"
        )

        if not template_file.exists():
            raise FileNotFoundError(f"Skill template not found: {template_file}")

        with open(template_file, 'r', encoding='utf-8') as f:
            return f.read()

    async def _generate_fill_data(self, skill_type: str, project_id: str,
                                  synthesis_report: str, technique_summary: Dict,
                                  sample_info: Dict) -> Dict:
        """生成模板填充数据"""
        self.logger.info(f"Generating fill data for {skill_type} skill...")

        # 构建prompt让LLM提取结构化数据
        prompt = self._build_extraction_prompt(
            skill_type=skill_type,
            synthesis_report=synthesis_report,
            technique_summary=technique_summary,
            sample_info=sample_info
        )

        # 调用LLM提取数据
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

        # 解析JSON
        fill_data = json.loads(llm_response["content"])

        # 添加基础数据
        fill_data.update({
            "project_id": project_id,
            "project_name": project_id.replace("_", " ").title(),
            "sample_count": len(sample_info.get("samples", [])),
            "sample_titles": ", ".join([s["title"] for s in sample_info.get("samples", [])]),
            "sample_list": "\n".join([
                f"- 《{s['title']}》（{s['total_chars']}字，{s['total_chapters']}章）"
                for s in sample_info.get("samples", [])
            ]),
            "created_at": datetime.now().isoformat(),
            "version": "1.0.0"
        })

        return fill_data

    def _build_extraction_prompt(self, skill_type: str, synthesis_report: str,
                                 technique_summary: Dict, sample_info: Dict) -> str:
        """构建数据提取prompt"""

        base_prompt = f"""
你是一位专业的写作技巧分析师。请基于以下跨书归纳报告和技巧统计，提取生成{skill_type} Skill所需的结构化数据。

## 技巧统计数据

{json.dumps(technique_summary, ensure_ascii=False, indent=2)}

## 跨书归纳报告（前3000字）

{synthesis_report[:3000]}

## 样本信息

总样本数：{len(sample_info.get("samples", []))}
样本列表：
{chr(10).join([f"- {s['title']}" for s in sample_info.get("samples", [])])}
"""

        if skill_type == "writing":
            specific_prompt = """
## 任务要求

请提取以下数据（以JSON格式返回）：

```json
{
  "prose_techniques": [
    {
      "index": 1,
      "technique_name": "技巧名称",
      "frequency": 85.5,
      "description": "技巧描述",
      "usage_scenario": "使用场景",
      "example": "示例文本",
      "suggestion": "使用建议"
    }
  ],
  "narrative_pace_description": "叙事节奏总体描述",
  "pace_points": ["节奏控制要点1", "节奏控制要点2"],
  "scene_battle": [
    {
      "technique": "技巧名称",
      "frequency": 75.0,
      "timing": "使用时机",
      "core_elements": "核心要素",
      "example": "示例片段",
      "notes": "注意事项"
    }
  ],
  "scene_daily": [...],
  "scene_emotion": [...],
  "conflict_techniques": [...],
  "appeal_types": [
    {"type": "爽点类型", "count": 50, "percentage": 30.0}
  ],
  "appeal_techniques": [...],
  "must_keep": [
    {"feature": "特征", "reason": "原因"}
  ],
  "flexible_elements": [
    {"element": "元素", "guideline": "指导"}
  ],
  "avoid_issues": [
    {"issue": "问题", "solution": "解决方案"}
  ],
  "target_word_count": "2500-4000"
}
```

注意：
1. 按频率从高到低排序
2. 提取TOP 5-10的技巧即可
3. 示例要简洁有代表性
4. 数据要基于实际统计
"""

        elif skill_type == "outline":
            specific_prompt = """
## 任务要求

请提取以下数据（以JSON格式返回）：

```json
{
  "word_count_range": "80-150万字",
  "volume_count_range": "5-10卷",
  "words_per_volume": "10-20万字",
  "chapters_per_volume": "50-100章",
  "volume_pattern": "卷结构模式描述",
  "standard_chapter_length": 3000,
  "chapter_length_range": "2500-4000",
  "special_chapter_length": "4000-6000",
  "chapter_types": [
    {"type": "类型", "percentage": 40.0, "description": "描述"}
  ],
  "rhythm_pattern_description": "节奏模式描述",
  "fast_pace_ratio": 40.0,
  "fast_pace_techniques": [...],
  "slow_pace_ratio": 30.0,
  "slow_pace_techniques": [...],
  "transition_types": [
    {"type": "转场类型", "count": 100, "percentage": 35.0}
  ],
  "expected_appeal_interval": 3,
  "max_slow_chapters": 5,
  "max_fast_chapters": 8,
  "max_appeal_gap": 5
}
```
"""

        else:  # review
            specific_prompt = """
## 任务要求

请提取以下数据（以JSON格式返回）：

```json
{
  "style_features": [
    {
      "feature": "风格特征",
      "description": "描述",
      "check_method": "检查方法",
      "common_deviation": "常见偏离",
      "fix_suggestion": "修改建议"
    }
  ],
  "high_freq_techniques": [...],
  "plot_issues": [
    {
      "issue": "问题",
      "manifestation": "表现形式",
      "check_method": "检查方法",
      "fix_method": "修改方案",
      "severity": "中等"
    }
  ],
  "character_issues": [...],
  "writing_issues": [...],
  "logic_issues": [...],
  "avoid_elements": [
    {
      "element": "元素",
      "reason": "原因",
      "alternative": "替代方案",
      "example": "示例"
    }
  ],
  "pass_criteria": ["及格标准1", "及格标准2"],
  "good_criteria": ["良好标准1", "良好标准2"],
  "excellent_criteria": ["优秀标准1", "优秀标准2"]
}
```
"""

        return base_prompt + specific_prompt

    async def _fill_template(self, template: str, fill_data: Dict) -> str:
        """填充模板"""
        # 简单的模板变量替换
        content = template

        # 替换简单变量 {{variable}}
        for key, value in fill_data.items():
            if isinstance(value, (str, int, float)):
                content = content.replace(f"{{{{{key}}}}}", str(value))

        # 处理列表循环 {{#list}} ... {{/list}}
        content = self._process_list_sections(content, fill_data)

        return content

    def _process_list_sections(self, content: str, data: Dict) -> str:
        """处理列表section"""
        import re

        # 匹配 {{#list_name}} ... {{/list_name}} 模式
        pattern = r'\{\{#(\w+)\}\}(.*?)\{\{/\1\}\}'

        def replace_section(match):
            list_name = match.group(1)
            section_template = match.group(2)

            if list_name not in data:
                return ""

            list_data = data[list_name]
            if not isinstance(list_data, list):
                return ""

            result = []
            for item in list_data:
                item_content = section_template
                if not isinstance(item, dict):
                    item_text = str(item)
                    item_content = item_content.replace("{{.}}", item_text)
                    item_content = item_content.replace("{{value}}", item_text)
                    item_content = item_content.replace("{{item}}", item_text)
                    item_content = item_content.replace("{{point}}", item_text)
                    item_content = item_content.replace("{{element}}", item_text)
                    result.append(item_content)
                    continue

                # 替换item中的变量
                for key, value in item.items():
                    if isinstance(value, (str, int, float)):
                        item_content = item_content.replace(f"{{{{{key}}}}}", str(value))
                    elif isinstance(value, list):
                        # 简单处理嵌套列表
                        item_content = item_content.replace(
                            f"{{{{{key}}}}}",
                            "\n".join([f"- {v}" for v in value])
                        )
                result.append(item_content)

            return "".join(result)

        return re.sub(pattern, replace_section, content, flags=re.DOTALL)

    async def _save_skill(self, project_id: str, skill_type: str, skill_content: str) -> Path:
        """保存Skill文件"""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        skills_dir = project_root / "skills" / "local"
        skills_dir.mkdir(parents=True, exist_ok=True)

        skill_file = skills_dir / f"{skill_type}_skill.md"
        with open(skill_file, 'w', encoding='utf-8') as f:
            f.write(skill_content)

        # 兼容旧版读取路径；新链路优先使用 projects/{id}/skills/local。
        legacy_dir = Path(settings.SKILLS_PATH) / project_id
        legacy_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(skill_file, legacy_dir / skill_file.name)

        self.logger.info(f"Saved {skill_type} skill to {skill_file}")
        return skill_file

    async def _write_enabled_yaml(self, project_id: str, generated_skills: List[Dict[str, Any]]) -> Path:
        """写入项目Skill启用配置。"""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id
        skills_dir = project_root / "skills"
        skills_dir.mkdir(parents=True, exist_ok=True)
        enabled_file = skills_dir / "enabled.yaml"

        config = {
            "version": "1.0.0",
            "project_id": project_id,
            "updated_at": datetime.now().isoformat(),
            "skills": [
                {
                    "name": skill["skill_name"],
                    "type": skill["skill_type"],
                    "path": skill["relative_path"],
                    "enabled": True,
                    "priority": self._skill_priority(skill["skill_type"]),
                    "scope": self._skill_scope(skill["skill_type"]),
                }
                for skill in generated_skills
            ]
        }

        with open(enabled_file, 'w', encoding='utf-8') as f:
            yaml.safe_dump(config, f, allow_unicode=True, sort_keys=False)

        self.logger.info(f"Updated enabled skills config: {enabled_file}")
        return enabled_file

    def _skill_priority(self, skill_type: str) -> int:
        priorities = {
            "writing": 100,
            "outline": 90,
            "review": 80,
        }
        return priorities.get(skill_type, 50)

    def _skill_scope(self, skill_type: str) -> List[str]:
        scopes = {
            "writing": ["chapter_writing", "style_guidance"],
            "outline": ["outline_generation", "chapter_boundary"],
            "review": ["chapter_review", "quality_check"],
        }
        return scopes.get(skill_type, ["general"])
