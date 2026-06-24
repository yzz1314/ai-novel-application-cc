"""
章节识别器
从文本中识别章节边界
"""
import re
from typing import List, Dict

class ChapterDetector:
    """章节识别器"""

    def __init__(self):
        # 中文章节标题模式
        self.patterns = [
            r'^第[零一二三四五六七八九十百千万\d]+章[：:\s]*.+$',
            r'^第[零一二三四五六七八九十百千万\d]+回[：:\s]*.+$',
            r'^Chapter\s+\d+[：:\s]*.+$',
            r'^#{1,3}\s*第[零一二三四五六七八九十百千万\d]+章',
            r'^\d+\.\s*.+$',  # 数字开头的标题
        ]

        self.compiled_patterns = [re.compile(p, re.MULTILINE | re.IGNORECASE) for p in self.patterns]

    def detect_chapters(self, text: str) -> List[Dict]:
        """
        检测章节边界

        Returns:
            [
                {
                    "chapter_index": 1,
                    "title": "第一章 重生",
                    "start_offset": 0,
                    "end_offset": 5234,
                    "char_count": 5234
                },
                ...
            ]
        """
        # 查找所有匹配的章节标题
        matches = []
        for pattern in self.compiled_patterns:
            for match in pattern.finditer(text):
                title = match.group().strip()
                matches.append({
                    "title": title,
                    "start": match.start(),
                    "end": match.end()
                })

        if not matches:
            # 没有找到章节标题，返回空列表
            return []

        # 按位置排序并去重
        matches = self._deduplicate_matches(matches)
        matches.sort(key=lambda x: x["start"])

        # 构建章节列表
        chapters = []
        for i, match in enumerate(matches):
            chapter_index = i + 1
            title = match["title"]
            start_offset = match["start"]

            # 章节结束位置是下一章的开始位置
            if i < len(matches) - 1:
                end_offset = matches[i + 1]["start"]
            else:
                end_offset = len(text)

            chapters.append({
                "chapter_index": chapter_index,
                "title": title,
                "start_offset": start_offset,
                "end_offset": end_offset,
                "char_count": end_offset - start_offset
            })

        return chapters

    def _deduplicate_matches(self, matches: List[Dict]) -> List[Dict]:
        """去重：如果两个匹配位置很接近，只保留一个"""
        if not matches:
            return []

        matches.sort(key=lambda x: x["start"])
        deduplicated = [matches[0]]

        for match in matches[1:]:
            last = deduplicated[-1]
            # 如果两个匹配距离小于10个字符，认为是同一个章节标题
            if match["start"] - last["start"] > 10:
                deduplicated.append(match)

        return deduplicated

    def extract_chapter_number(self, title: str) -> int:
        """从标题中提取章节号"""
        # 中文数字映射
        chinese_nums = {
            '零': 0, '一': 1, '二': 2, '三': 3, '四': 4,
            '五': 5, '六': 6, '七': 7, '八': 8, '九': 9,
            '十': 10, '百': 100, '千': 1000, '万': 10000
        }

        # 尝试提取阿拉伯数字
        match = re.search(r'\d+', title)
        if match:
            return int(match.group())

        # 尝试提取中文数字
        match = re.search(r'[零一二三四五六七八九十百千万]+', title)
        if match:
            return self._chinese_to_arabic(match.group(), chinese_nums)

        return 0

    def _chinese_to_arabic(self, chinese: str, mapping: Dict[str, int]) -> int:
        """中文数字转阿拉伯数字"""
        result = 0
        temp = 0
        unit = 1

        for char in reversed(chinese):
            num = mapping.get(char, 0)

            if num >= 10:
                if num > unit:
                    unit = num
                else:
                    unit *= num
            else:
                temp += num * unit

        result += temp
        return result
