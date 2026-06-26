"""
文本规范化器
将原始小说文本规范化为统一格式
"""
import re
from typing import Tuple

class TextNormalizer:
    """文本规范化器"""

    def __init__(self):
        # 需要替换的字符映射
        self.char_replacements = {
            '\ufeff': '',
            '…': '...',
            '—': '-',
            '―': '-',
            '–': '-',
            ''': "'",
            ''': "'",
            '"': '"',
            '"': '"',
            '　': ' ',  # 全角空格
        }

    def normalize(self, text: str) -> Tuple[str, dict]:
        """
        规范化文本

        Args:
            text: 原始文本

        Returns:
            (normalized_text, stats): 规范化后的文本和统计信息
        """
        original_length = len(text)

        # 1. 字符替换（包含 UTF-8 BOM）
        text = self._replace_chars(text)

        # 2. 空白字符规范化
        text = self._normalize_whitespace(text)

        # 3. 段落规范化
        text = self._normalize_paragraphs(text)

        # 4. 统计信息
        stats = {
            'original_length': original_length,
            'normalized_length': len(text),
            'char_count': len(text),
            'line_count': text.count('\n'),
        }

        return text, stats

    def _replace_chars(self, text: str) -> str:
        """替换特殊字符"""
        for old, new in self.char_replacements.items():
            text = text.replace(old, new)
        return text

    def _normalize_whitespace(self, text: str) -> str:
        """规范化空白字符"""
        # 移除行尾空白
        text = re.sub(r'[ \t]+$', '', text, flags=re.MULTILINE)

        # 移除行首空白（但保留段落缩进）
        lines = []
        for line in text.split('\n'):
            # 如果行首有2个以上空格，保留2个，否则移除所有
            if line.startswith('  '):
                line = '  ' + line.lstrip()
            else:
                line = line.lstrip()
            lines.append(line)

        text = '\n'.join(lines)

        # 规范化多个连续空格为一个
        text = re.sub(r' {2,}', ' ', text)

        return text

    def _normalize_paragraphs(self, text: str) -> str:
        """规范化段落"""
        # 移除3个以上连续换行，保留最多2个
        text = re.sub(r'\n{3,}', '\n\n', text)

        # 确保文本开头和结尾没有多余空行
        text = text.strip()

        return text

    def detect_title(self, text: str) -> str:
        """
        从文本中检测标题

        通常标题在文件开头，单独一行
        """
        lines = text.split('\n')
        for line in lines[:10]:  # 只检查前10行
            line = line.strip()
            if line and len(line) < 100 and not line.startswith('#'):
                # 可能是标题
                return line

        return ""
