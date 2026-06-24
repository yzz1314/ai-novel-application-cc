"""
文本分块器
将文本分割成适合处理的块
"""
from typing import List, Dict

class Chunker:
    """文本分块器"""

    def __init__(self, max_chunk_size: int = 2500, overlap: int = 200):
        self.max_chunk_size = max_chunk_size
        self.overlap = overlap

    def chunk_by_chapters(self, text: str, chapters: List[Dict]) -> List[Dict]:
        """
        按章节分块

        Args:
            text: 完整文本
            chapters: 章节列表

        Returns:
            分块列表
        """
        chunks = []

        for chapter in chapters:
            chapter_text = text[chapter["start_offset"]:chapter["end_offset"]]

            # 如果章节长度小于最大块大小，直接作为一个块
            if len(chapter_text) <= self.max_chunk_size:
                chunks.append({
                    "id": f"chunk_{len(chunks):04d}",
                    "chapter_index": chapter["chapter_index"],
                    "part_index": 0,
                    "start_offset": chapter["start_offset"],
                    "end_offset": chapter["end_offset"],
                    "char_count": len(chapter_text),
                    "text": chapter_text,
                    "heading_path": chapter["title"]
                })
            else:
                # 章节过长，需要进一步分割
                sub_chunks = self._split_long_chapter(
                    chapter_text,
                    chapter["start_offset"],
                    chapter["chapter_index"],
                    chapter["title"]
                )
                chunks.extend(sub_chunks)

        return chunks

    def _split_long_chapter(self, text: str, base_offset: int,
                          chapter_index: int, chapter_title: str) -> List[Dict]:
        """分割长章节"""
        chunks = []
        part_index = 0
        start = 0

        while start < len(text):
            end = start + self.max_chunk_size

            # 如果不是最后一块，尝试在句号、问号、感叹号处断开
            if end < len(text):
                # 寻找最近的句子边界
                best_break = end
                for i in range(end, max(start + self.max_chunk_size // 2, end - 200), -1):
                    if i < len(text) and text[i] in '。！？\n':
                        best_break = i + 1
                        break
                end = best_break

            chunk_text = text[start:end]

            chunks.append({
                "id": f"chunk_{len(chunks):04d}",
                "chapter_index": chapter_index,
                "part_index": part_index,
                "start_offset": base_offset + start,
                "end_offset": base_offset + end,
                "char_count": len(chunk_text),
                "text": chunk_text,
                "heading_path": f"{chapter_title} (Part {part_index + 1})"
            })

            # 下一块从overlap位置开始
            if end < len(text):
                start = end - self.overlap
            else:
                start = end

            part_index += 1

        return chunks

    def chunk_without_chapters(self, text: str) -> List[Dict]:
        """
        无章节标题时按固定大小分块

        用于没有明确章节划分的文本
        """
        chunks = []
        start = 0
        synthetic_chapter_index = 1

        while start < len(text):
            end = min(start + self.max_chunk_size, len(text))

            # 尝试在句子边界断开
            if end < len(text):
                for i in range(end, max(start + self.max_chunk_size // 2, end - 200), -1):
                    if text[i] in '。！？\n':
                        end = i + 1
                        break

            chunk_text = text[start:end]

            chunks.append({
                "id": f"chunk_{len(chunks):04d}",
                "chapter_index": synthetic_chapter_index,
                "part_index": 0,
                "start_offset": start,
                "end_offset": end,
                "char_count": len(chunk_text),
                "text": chunk_text,
                "heading_path": f"Synthetic Chapter {synthetic_chapter_index}",
                "is_synthetic": True
            })

            if end < len(text):
                start = end - self.overlap
            else:
                start = end

            synthetic_chapter_index += 1

        return chunks
