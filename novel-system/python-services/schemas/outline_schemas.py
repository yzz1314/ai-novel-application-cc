"""
大纲相关数据模型
"""
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from datetime import datetime


class CharacterSchema(BaseModel):
    """人物设定"""
    name: str = Field(..., description="人物名称")
    role: str = Field(..., description="角色类型：主角/配角/反派/路人")
    description: str = Field(default="", description="人物描述")
    attributes: Dict[str, Any] = Field(default_factory=dict, description="人物属性")
    relationships: List[str] = Field(default_factory=list, description="关系列表")
    introduction_chapter: Optional[int] = Field(None, description="登场章节")


class WorldSettingSchema(BaseModel):
    """世界观设定"""
    name: str = Field(..., description="设定名称")
    category: str = Field(..., description="设定类别：地点/组织/规则/物品/技能")
    description: str = Field(..., description="详细描述")
    related_entities: List[str] = Field(default_factory=list, description="相关实体")


class ChapterOutlineSchema(BaseModel):
    """章节大纲"""
    chapter_number: int = Field(..., description="章节序号")
    chapter_title: str = Field(..., description="章节标题")
    target_word_count: int = Field(default=3000, description="目标字数")

    # 本章目标
    plot_goal: str = Field(..., description="剧情推进目标")
    character_development: str = Field(default="", description="人物发展")
    info_reveal: str = Field(default="", description="信息揭示")

    # 场景设计
    scenes: List[Dict[str, Any]] = Field(default_factory=list, description="场景列表")

    # 关键要素
    conflict: str = Field(default="", description="本章冲突")
    appeal_point: str = Field(default="", description="爽点设计")
    suspense: str = Field(default="", description="悬念/伏笔")

    # 承接关系
    connect_previous: str = Field(default="", description="承接上章")
    lead_to_next: str = Field(default="", description="引出下章")

    # 边界控制
    core_goal: str = Field(default="", description="本章核心目标")
    must_write: List[str] = Field(default_factory=list, description="本章必须完成的内容")
    allowed_progress: List[str] = Field(default_factory=list, description="可轻微铺垫但不得完成的内容")
    must_not_write: List[str] = Field(default_factory=list, description="本章禁止写入的内容")
    reserved_for_future: Dict[str, str] = Field(default_factory=dict, description="保留给后续章节的内容")
    stop_point: str = Field(default="", description="本章停止点")
    ending_hook: str = Field(default="", description="章末钩子")

    # 节奏和密度
    pace_type: str = Field(default="medium", description="节奏类型：fast/medium/slow")
    info_density: str = Field(default="medium", description="信息密度：high/medium/low")


class VolumeOutlineSchema(BaseModel):
    """分卷大纲"""
    volume_number: int = Field(..., description="卷序号")
    volume_title: str = Field(..., description="卷标题")
    target_word_count: int = Field(default=100000, description="目标字数")
    target_chapters: int = Field(default=50, description="目标章节数")

    # 核心目标
    main_goal: str = Field(..., description="主线目标")
    sub_goals: List[str] = Field(default_factory=list, description="副线目标")
    character_growth: str = Field(..., description="人物成长")

    # 主要冲突
    external_conflict: str = Field(..., description="外部冲突")
    internal_conflict: str = Field(default="", description="内部冲突")
    resolution: str = Field(..., description="解决方式")

    # 关键节点
    opening_node: Dict[str, Any] = Field(..., description="开卷节点")
    quarter_node: Dict[str, Any] = Field(..., description="1/4节点")
    midpoint_node: Dict[str, Any] = Field(..., description="中点节点")
    three_quarter_node: Dict[str, Any] = Field(..., description="3/4节点")
    climax_node: Dict[str, Any] = Field(..., description="卷末高潮")

    # 悬念布局
    new_suspense: List[str] = Field(default_factory=list, description="本卷新增悬念")
    resolved_suspense: List[str] = Field(default_factory=list, description="解决的悬念")
    ongoing_suspense: List[str] = Field(default_factory=list, description="推进的长线悬念")

    # 爽点计划
    major_appeal_points: List[Dict[str, Any]] = Field(default_factory=list, description="大爽点")
    appeal_distribution: str = Field(default="", description="小爽点分布")

    # 新增要素
    new_characters: List[str] = Field(default_factory=list, description="新角色")
    new_locations: List[str] = Field(default_factory=list, description="新地点")
    new_settings: List[str] = Field(default_factory=list, description="新设定")

    # 章节列表
    chapters: List[ChapterOutlineSchema] = Field(default_factory=list, description="章节大纲列表")


class BookOutlineSchema(BaseModel):
    """书籍大纲"""
    # 基本信息
    book_id: str = Field(..., description="书籍ID")
    project_id: str = Field(..., description="项目ID")
    book_title: str = Field(..., description="书名")
    genre: str = Field(..., description="类型/题材")
    target_word_count: int = Field(..., description="目标总字数")
    target_audience: str = Field(default="", description="目标受众")

    # 核心设定
    core_concept: str = Field(..., description="核心概念/卖点")
    world_view: str = Field(..., description="世界观概述")
    main_conflict: str = Field(..., description="核心冲突")
    theme: str = Field(default="", description="主题")

    # 人物设定
    characters: List[CharacterSchema] = Field(default_factory=list, description="人物列表")

    # 世界观设定
    world_settings: List[WorldSettingSchema] = Field(default_factory=list, description="世界观设定列表")

    # 分卷规划
    volumes: List[VolumeOutlineSchema] = Field(default_factory=list, description="分卷列表")

    # 整体规划
    rhythm_curve: str = Field(default="", description="整体节奏曲线描述")
    long_term_suspense: List[str] = Field(default_factory=list, description="长线悬念列表")

    # 使用的Skills
    used_skills: List[str] = Field(default_factory=list, description="使用的Skill名称")

    # 元数据
    version: str = Field(default="1.0.0", description="版本号")
    status: str = Field(default="draft", description="状态：draft/reviewing/approved")
    created_at: datetime = Field(default_factory=datetime.now, description="创建时间")
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")
    created_by: str = Field(default="system", description="创建者")

    # 统计信息
    total_volumes: int = Field(default=0, description="总卷数")
    total_chapters: int = Field(default=0, description="总章节数")

    class Config:
        json_schema_extra = {
            "example": {
                "book_id": "book_001",
                "project_id": "project_001",
                "book_title": "剑道独尊",
                "genre": "玄幻修真",
                "target_word_count": 1000000,
                "target_audience": "男性读者，18-35岁",
                "core_concept": "废材少年逆袭成为剑道巅峰",
                "world_view": "修真世界，以剑道为主",
                "main_conflict": "主角与天才弟子、宗门势力的对抗",
                "volumes": [],
                "total_volumes": 5,
                "total_chapters": 250
            }
        }


class OutlineGenerationRequest(BaseModel):
    """大纲生成请求"""
    project_id: str = Field(..., description="项目ID")

    # 创作需求
    book_title: str = Field(..., description="书名")
    genre: str = Field(..., description="类型/题材")
    target_word_count: int = Field(..., description="目标总字数")
    target_volumes: int = Field(default=5, description="目标卷数")

    # 核心设定
    core_concept: str = Field(..., description="核心概念/卖点")
    world_view: str = Field(default="", description="世界观设定")
    main_characters: str = Field(default="", description="主要人物设定")
    main_conflict: str = Field(default="", description="核心冲突")

    # 参考信息
    reference_works: List[str] = Field(default_factory=list, description="参考作品")
    special_requirements: str = Field(default="", description="特殊要求")

    # Skills配置
    use_project_skills: bool = Field(default=True, description="是否使用项目Skills")
    custom_skill_names: List[str] = Field(default_factory=list, description="自定义Skill名称")


class OutlineGenerationResponse(BaseModel):
    """大纲生成响应"""
    book_id: str = Field(..., description="生成的书籍ID")
    outline: BookOutlineSchema = Field(..., description="完整大纲")
    generation_summary: Dict[str, Any] = Field(default_factory=dict, description="生成摘要")
    warnings: List[str] = Field(default_factory=list, description="警告信息")
