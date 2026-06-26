"""
章节创作相关数据模型
"""
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from datetime import datetime


class ChapterWriteRequest(BaseModel):
    """章节创作请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")
    volume_number: int = Field(..., description="卷号")
    chapter_number: int = Field(..., description="章节号")

    # 可选：覆盖大纲中的设置
    chapter_title: Optional[str] = Field(None, description="章节标题（可覆盖大纲）")
    target_word_count: Optional[int] = Field(None, description="目标字数（可覆盖大纲）")

    # 创作配置
    use_project_skills: bool = Field(default=True, description="是否使用项目Skills")
    use_previous_context: bool = Field(default=True, description="是否使用前文上下文")
    context_chapters: int = Field(default=3, description="参考前几章作为上下文")

    # 创作风格调整
    creativity_level: float = Field(default=0.7, description="创意程度 0-1")
    detail_level: str = Field(default="medium", description="细节程度：light/medium/rich")

    # 是否进行审查
    auto_review: bool = Field(default=True, description="是否自动审查")
    review_iterations: int = Field(default=1, description="审查迭代次数")


class ChapterRevisionRequest(BaseModel):
    """章节返修请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")
    volume_number: int = Field(..., description="卷号")
    chapter_number: int = Field(..., description="章节号")

    # 返修目标
    source_stage: str = Field(default="draft", description="返修来源：draft/final/auto")
    user_instruction: str = Field(default="", description="人工返修要求")
    issues: List[Dict[str, Any]] = Field(default_factory=list, description="外部传入的问题清单")
    include_boundary_warnings: bool = Field(default=True, description="是否把边界警告也纳入返修")

    # 执行配置
    use_project_skills: bool = Field(default=True, description="是否使用项目Skills")
    max_iterations: int = Field(default=1, description="返修轮数")
    create_version_snapshot: bool = Field(default=True, description="返修前是否归档版本快照")


class ChapterContent(BaseModel):
    """章节内容"""
    chapter_id: str = Field(..., description="章节ID")
    book_id: str = Field(..., description="书籍ID")
    volume_number: int = Field(..., description="卷号")
    chapter_number: int = Field(..., description="章节号")
    chapter_title: str = Field(..., description="章节标题")

    # 正文内容
    content: str = Field(..., description="章节正文")
    word_count: int = Field(..., description="实际字数")

    # 创作信息
    based_on_outline: bool = Field(default=True, description="是否基于大纲")
    outline_summary: str = Field(default="", description="大纲摘要")

    # 使用的资源
    used_skills: List[str] = Field(default_factory=list, description="使用的Skills")
    referenced_chapters: List[int] = Field(default_factory=list, description="参考的前文章节")

    # 质量指标
    quality_score: Optional[float] = Field(None, description="质量评分 0-100")
    review_status: str = Field(default="draft", description="审查状态：draft/reviewed/approved")
    review_comments: List[str] = Field(default_factory=list, description="审查意见")
    boundary_check: Dict[str, Any] = Field(default_factory=dict, description="章节边界检查结果")
    revision_history: List[Dict[str, Any]] = Field(default_factory=list, description="返修历史")

    # 元数据
    version: int = Field(default=1, description="版本号")
    created_at: datetime = Field(default_factory=datetime.now, description="创建时间")
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")
    created_by: str = Field(default="system", description="创建者")


class ChapterReview(BaseModel):
    """章节审查结果"""
    chapter_id: str = Field(..., description="章节ID")

    # 评分维度
    style_consistency: float = Field(..., description="风格一致性 0-10")
    technique_usage: float = Field(..., description="技巧运用 0-10")
    quality_level: float = Field(..., description="质量水平 0-10")
    continuity: float = Field(..., description="连续性 0-10")
    structure: float = Field(..., description="结构完整性 0-10")

    # 总评
    total_score: float = Field(..., description="总分 0-50")
    overall_rating: str = Field(..., description="总体评级：excellent/good/pass/fail")

    # 发现的问题
    issues: List[Dict[str, Any]] = Field(default_factory=list, description="问题列表")

    # 修改建议
    suggestions: List[str] = Field(default_factory=list, description="修改建议")

    # 优点记录
    strengths: List[str] = Field(default_factory=list, description="优点")

    # 审查结论
    pass_review: bool = Field(..., description="是否通过审查")
    needs_revision: bool = Field(default=False, description="是否需要修改")


class BatchChapterWriteRequest(BaseModel):
    """批量章节创作请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")
    volume_number: int = Field(..., description="卷号")

    # 章节范围
    start_chapter: int = Field(..., description="起始章节号")
    end_chapter: int = Field(..., description="结束章节号")

    # 创作配置（同ChapterWriteRequest）
    use_project_skills: bool = Field(default=True, description="是否使用项目Skills")
    creativity_level: float = Field(default=0.7, description="创意程度 0-1")
    auto_review: bool = Field(default=True, description="是否自动审查")

    # 批量配置
    parallel_count: int = Field(default=1, description="并行创作数量")
    continue_on_error: bool = Field(default=True, description="遇到错误是否继续")


class ChapterWriteResponse(BaseModel):
    """章节创作响应"""
    chapter_id: str = Field(..., description="章节ID")
    chapter_content: ChapterContent = Field(..., description="章节内容")
    review_result: Optional[ChapterReview] = Field(None, description="审查结果")

    # 创作统计
    generation_time_ms: int = Field(..., description="生成耗时（毫秒）")
    llm_calls: int = Field(..., description="LLM调用次数")
    total_tokens: int = Field(..., description="总Token数")

    # 警告
    warnings: List[str] = Field(default_factory=list, description="警告信息")


class BatchChapterWriteResponse(BaseModel):
    """批量章节创作响应"""
    book_id: str = Field(..., description="书籍ID")
    volume_number: int = Field(..., description="卷号")

    # 成功的章节
    succeeded_chapters: List[ChapterWriteResponse] = Field(default_factory=list, description="成功的章节")

    # 失败的章节
    failed_chapters: List[Dict[str, Any]] = Field(default_factory=list, description="失败的章节")

    # 统计信息
    total_requested: int = Field(..., description="请求总数")
    total_succeeded: int = Field(..., description="成功数量")
    total_failed: int = Field(..., description="失败数量")

    # 总体统计
    total_words: int = Field(default=0, description="总字数")
    total_time_ms: int = Field(default=0, description="总耗时")
    average_quality: Optional[float] = Field(None, description="平均质量评分")
