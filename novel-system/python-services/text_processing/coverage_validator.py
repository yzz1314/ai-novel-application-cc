"""
覆盖率校验器
确保文本100%被处理
"""
from typing import List, Dict, Tuple

class CoverageValidator:
    """覆盖率校验器"""

    def validate(self, total_chars: int, chunks: List[Dict]) -> Dict:
        """
        验证分块覆盖率

        Args:
            total_chars: 总字符数
            chunks: 分块列表

        Returns:
            {
                "total_chars": int,
                "processed_chars": int,
                "coverage_ratio": float,
                "is_complete": bool,
                "missing_ranges": [(start, end), ...]
            }
        """
        # 排序chunks
        sorted_chunks = sorted(chunks, key=lambda x: x["start_offset"])

        # 合并重叠区间
        merged_ranges = self._merge_ranges([
            (chunk["start_offset"], chunk["end_offset"])
            for chunk in sorted_chunks
        ])

        # 计算处理字符数
        processed_chars = sum(end - start for start, end in merged_ranges)

        # 找出未覆盖区间
        missing_ranges = self._find_missing_ranges(merged_ranges, total_chars)

        coverage_ratio = processed_chars / total_chars if total_chars > 0 else 0

        return {
            "total_chars": total_chars,
            "processed_chars": processed_chars,
            "coverage_ratio": coverage_ratio,
            "is_complete": coverage_ratio >= 0.999,  # 允许0.1%误差
            "missing_ranges": missing_ranges
        }

    def _merge_ranges(self, ranges: List[Tuple[int, int]]) -> List[Tuple[int, int]]:
        """合并重叠区间"""
        if not ranges:
            return []

        ranges = sorted(ranges)
        merged = [ranges[0]]

        for start, end in ranges[1:]:
            last_start, last_end = merged[-1]

            if start <= last_end:
                # 有重叠，合并
                merged[-1] = (last_start, max(last_end, end))
            else:
                # 无重叠，添加新区间
                merged.append((start, end))

        return merged

    def _find_missing_ranges(self, covered_ranges: List[Tuple[int, int]],
                           total_chars: int) -> List[Tuple[int, int]]:
        """找出未覆盖区间"""
        missing = []
        expected_start = 0

        for start, end in covered_ranges:
            if start > expected_start:
                missing.append((expected_start, start))
            expected_start = max(expected_start, end)

        # 检查结尾
        if expected_start < total_chars:
            missing.append((expected_start, total_chars))

        return missing

    def generate_repair_chunks(self, missing_ranges: List[Tuple[int, int]],
                              text: str, max_chunk_size: int = 2500) -> List[Dict]:
        """
        为缺失区间生成补充chunk

        Args:
            missing_ranges: 缺失区间列表
            text: 完整文本
            max_chunk_size: 最大块大小

        Returns:
            补充chunk列表
        """
        repair_chunks = []
        chunk_id_base = 9000  # 使用9000+作为修复chunk的ID

        for i, (start, end) in enumerate(missing_ranges):
            missing_text = text[start:end]

            # 如果缺失区间较小，直接创建一个chunk
            if len(missing_text) <= max_chunk_size:
                repair_chunks.append({
                    "id": f"chunk_{chunk_id_base + i:04d}",
                    "chapter_index": -1,  # 标记为修复chunk
                    "part_index": 0,
                    "start_offset": start,
                    "end_offset": end,
                    "char_count": len(missing_text),
                    "text": missing_text,
                    "heading_path": f"Repair Chunk {i + 1}",
                    "is_repair": True
                })
            else:
                # 缺失区间较大，需要分割
                sub_start = start
                part_index = 0
                while sub_start < end:
                    sub_end = min(sub_start + max_chunk_size, end)
                    sub_text = text[sub_start:sub_end]

                    repair_chunks.append({
                        "id": f"chunk_{chunk_id_base + i:04d}_{part_index}",
                        "chapter_index": -1,
                        "part_index": part_index,
                        "start_offset": sub_start,
                        "end_offset": sub_end,
                        "char_count": len(sub_text),
                        "text": sub_text,
                        "heading_path": f"Repair Chunk {i + 1} Part {part_index + 1}",
                        "is_repair": True
                    })

                    sub_start = sub_end
                    part_index += 1

        return repair_chunks
