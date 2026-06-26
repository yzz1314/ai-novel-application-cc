"""
Skill加载器
负责加载和管理项目Skill
"""
from pathlib import Path
from typing import Dict, Any, Optional, List
import yaml
import re

from config import settings
from utils.logger import get_logger


class SkillLoader:
    """Skill加载器"""

    def __init__(self, skills_path: Optional[str] = None):
        self.skills_path = Path(skills_path or settings.SKILLS_PATH)
        self.logger = get_logger("SkillLoader")
        self._skills_cache: Dict[str, Dict] = {}

    def load_skill(self, skill_name: str) -> Dict[str, Any]:
        """
        加载指定的Skill

        Args:
            skill_name: Skill名称（不含扩展名）

        Returns:
            {
                "name": str,
                "content": str,
                "metadata": dict,
                "sections": dict
            }
        """
        # 检查缓存
        if skill_name in self._skills_cache:
            self.logger.info(f"Loading skill from cache: {skill_name}")
            return self._skills_cache[skill_name]

        skill_file = self.skills_path / f"{skill_name}.md"

        if not skill_file.exists():
            raise FileNotFoundError(f"Skill file not found: {skill_file}")

        self.logger.info(f"Loading skill: {skill_name}")

        with open(skill_file, 'r', encoding='utf-8') as f:
            content = f.read()

        # 解析Skill内容
        skill_data = self._parse_skill(skill_name, content)

        # 缓存
        self._skills_cache[skill_name] = skill_data

        return skill_data

    def _parse_skill(self, skill_name: str, content: str) -> Dict[str, Any]:
        """解析Skill内容"""

        # 提取frontmatter（如果有）
        metadata = self._extract_frontmatter(content)

        # 移除frontmatter后的内容
        content_without_frontmatter = self._remove_frontmatter(content)

        # 解析各个section
        sections = self._parse_sections(content_without_frontmatter)

        return {
            "name": skill_name,
            "content": content_without_frontmatter,
            "full_content": content,
            "metadata": metadata,
            "sections": sections
        }

    def _extract_frontmatter(self, content: str) -> Dict[str, Any]:
        """提取YAML frontmatter"""
        frontmatter_pattern = r'^---\s*\n(.*?)\n---\s*\n'
        match = re.match(frontmatter_pattern, content, re.DOTALL)

        if match:
            try:
                return yaml.safe_load(match.group(1)) or {}
            except yaml.YAMLError as e:
                self.logger.warning(f"Failed to parse frontmatter: {e}")
                return {}

        return {}

    def _remove_frontmatter(self, content: str) -> str:
        """移除frontmatter"""
        frontmatter_pattern = r'^---\s*\n.*?\n---\s*\n'
        return re.sub(frontmatter_pattern, '', content, count=1, flags=re.DOTALL)

    def _parse_sections(self, content: str) -> Dict[str, str]:
        """解析Markdown sections"""
        sections = {}

        # 按一级标题分割
        parts = re.split(r'^#\s+(.+?)$', content, flags=re.MULTILINE)

        # parts[0]是标题前的内容，之后是[标题, 内容, 标题, 内容...]
        if len(parts) > 1:
            for i in range(1, len(parts), 2):
                if i + 1 < len(parts):
                    section_title = parts[i].strip()
                    section_content = parts[i + 1].strip()
                    sections[section_title] = section_content

        return sections

    def list_skills(self) -> List[Dict[str, Any]]:
        """列出所有可用的Skills"""
        if not self.skills_path.exists():
            return []

        skills = []
        for skill_file in self.skills_path.glob("*.md"):
            skill_name = skill_file.stem

            try:
                # 读取文件的前几行获取基本信息
                with open(skill_file, 'r', encoding='utf-8') as f:
                    content = f.read(500)  # 只读取前500字符

                metadata = self._extract_frontmatter(content)

                skills.append({
                    "name": skill_name,
                    "file": str(skill_file),
                    "title": metadata.get("title", skill_name),
                    "description": metadata.get("description", ""),
                    "version": metadata.get("version", "1.0.0")
                })
            except Exception as e:
                self.logger.warning(f"Failed to read skill {skill_name}: {e}")

        return skills

    def get_skill_section(self, skill_name: str, section_name: str) -> Optional[str]:
        """获取Skill的特定section"""
        skill = self.load_skill(skill_name)
        return skill["sections"].get(section_name)

    def reload_skill(self, skill_name: str) -> Dict[str, Any]:
        """重新加载Skill（清除缓存）"""
        if skill_name in self._skills_cache:
            del self._skills_cache[skill_name]

        return self.load_skill(skill_name)

    def clear_cache(self):
        """清除所有缓存"""
        self._skills_cache.clear()
        self.logger.info("Skill cache cleared")

    def validate_skill(self, skill_name: str) -> Dict[str, Any]:
        """验证Skill的有效性"""
        try:
            skill = self.load_skill(skill_name)

            issues = []
            warnings = []

            # 检查必需的metadata
            required_metadata = ["title", "description"]
            for field in required_metadata:
                if field not in skill["metadata"]:
                    warnings.append(f"Missing recommended metadata: {field}")

            # 检查内容长度
            if len(skill["content"]) < 100:
                warnings.append("Skill content is very short (< 100 chars)")

            # 检查是否有sections
            if not skill["sections"]:
                warnings.append("No sections found in skill")

            return {
                "valid": len(issues) == 0,
                "issues": issues,
                "warnings": warnings,
                "skill_name": skill_name,
                "metadata": skill["metadata"],
                "section_count": len(skill["sections"]),
                "content_length": len(skill["content"])
            }

        except Exception as e:
            return {
                "valid": False,
                "issues": [str(e)],
                "warnings": [],
                "skill_name": skill_name
            }

    def create_skill_template(self, skill_name: str, title: str,
                             description: str) -> str:
        """创建Skill模板"""
        template = f"""---
title: {title}
description: {description}
version: 1.0.0
author: System
created_at: {__import__('datetime').datetime.now().isoformat()}
---

# {title}

{description}

## 分析目标

在此描述分析的具体目标和关注点。

## 分析维度

### 文笔技巧

描述要关注的文笔技巧...

### 情节技巧

描述要关注的情节技巧...

### 人物塑造

描述要关注的人物塑造技巧...

## 输出要求

描述期望的输出格式和内容...

## 注意事项

列出分析时需要注意的事项...
"""
        return template

    def save_skill(self, skill_name: str, content: str) -> Path:
        """保存Skill"""
        skill_file = self.skills_path / f"{skill_name}.md"

        # 确保目录存在
        self.skills_path.mkdir(parents=True, exist_ok=True)

        with open(skill_file, 'w', encoding='utf-8') as f:
            f.write(content)

        self.logger.info(f"Saved skill to {skill_file}")

        # 清除缓存
        if skill_name in self._skills_cache:
            del self._skills_cache[skill_name]

        return skill_file
