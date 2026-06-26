"""
记忆系统相关数据模型
"""
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from datetime import datetime


class CharacterMemory(BaseModel):
    """人物记忆"""
    character_id: str = Field(..., description="人物ID")
    name: str = Field(..., description="人物名称")
    aliases: List[str] = Field(default_factory=list, description="别名列表")

    # 基本信息
    role: str = Field(..., description="角色类型：protagonist/supporting/antagonist")
    description: str = Field(..., description="人物描述")

    # 当前状态
    current_status: Dict[str, Any] = Field(default_factory=dict, description="当前状态")
    # 例如: {"境界": "筑基期", "位置": "天剑宗", "状态": "正常"}

    # 状态历史
    status_history: List[Dict[str, Any]] = Field(default_factory=list, description="状态变化历史")
    # 例如: [{"chapter": 10, "境界": "练气期 -> 筑基期", "event": "突破"}]

    # 人物关系
    relationships: Dict[str, str] = Field(default_factory=dict, description="人物关系")
    # 例如: {"李云": "师兄", "王雪": "爱慕对象"}

    # 出场记录
    appearances: List[int] = Field(default_factory=list, description="出场章节列表")
    last_appearance: Optional[int] = Field(None, description="最后出场章节")

    # 重要事件
    important_events: List[Dict[str, Any]] = Field(default_factory=list, description="重要事件")
    # 例如: [{"chapter": 15, "event": "与主角结拜", "impact": "关系升级"}]

    # 元数据
    first_mentioned: int = Field(..., description="首次提及章节")
    last_updated: int = Field(..., description="最后更新章节")
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")


class WorldSettingMemory(BaseModel):
    """世界观设定记忆"""
    setting_id: str = Field(..., description="设定ID")
    name: str = Field(..., description="设定名称")
    category: str = Field(..., description="类别：location/organization/rule/item/skill")

    # 基本信息
    description: str = Field(..., description="详细描述")
    properties: Dict[str, Any] = Field(default_factory=dict, description="属性")

    # 相关实体
    related_characters: List[str] = Field(default_factory=list, description="相关人物")
    related_settings: List[str] = Field(default_factory=list, description="相关设定")

    # 状态变化
    status_changes: List[Dict[str, Any]] = Field(default_factory=list, description="状态变化")
    # 例如: [{"chapter": 20, "change": "天剑宗被攻击", "impact": "宗门危机"}]

    # 提及记录
    mentions: List[int] = Field(default_factory=list, description="提及章节列表")
    first_mentioned: int = Field(..., description="首次提及章节")
    last_updated: int = Field(..., description="最后更新章节")
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")


class PlotMemory(BaseModel):
    """剧情记忆"""
    plot_id: str = Field(..., description="剧情ID")
    plot_type: str = Field(..., description="剧情类型：main/sub/event")

    # 剧情信息
    title: str = Field(..., description="剧情标题")
    description: str = Field(..., description="剧情描述")

    # 时间范围
    start_chapter: int = Field(..., description="起始章节")
    end_chapter: Optional[int] = Field(None, description="结束章节（None表示进行中）")

    # 涉及要素
    involved_characters: List[str] = Field(default_factory=list, description="涉及人物")
    involved_settings: List[str] = Field(default_factory=list, description="涉及设定")

    # 关键事件
    key_events: List[Dict[str, Any]] = Field(default_factory=list, description="关键事件")
    # 例如: [{"chapter": 15, "event": "主角突破", "consequence": "实力提升"}]

    # 状态
    status: str = Field(default="ongoing", description="状态：ongoing/completed/abandoned")

    # 元数据
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")


class SuspenseMemory(BaseModel):
    """悬念记忆"""
    suspense_id: str = Field(..., description="悬念ID")
    suspense_type: str = Field(..., description="悬念类型：long/medium/short")

    # 悬念信息
    title: str = Field(..., description="悬念标题")
    description: str = Field(..., description="悬念描述")
    question: str = Field(..., description="悬念问题")

    # 时间范围
    set_chapter: int = Field(..., description="设置章节")
    resolved_chapter: Optional[int] = Field(None, description="解决章节（None表示未解决）")

    # 维持方式
    maintenance: List[str] = Field(default_factory=list, description="维持手法")

    # 状态
    status: str = Field(default="active", description="状态：active/resolved/abandoned")
    resolution: Optional[str] = Field(None, description="解决方式")

    # 元数据
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")


class TimelineEvent(BaseModel):
    """时间线事件"""
    event_id: str = Field(..., description="事件ID")
    chapter: int = Field(..., description="发生章节")

    # 事件信息
    event_type: str = Field(..., description="事件类型：battle/breakthrough/meeting/conflict")
    title: str = Field(..., description="事件标题")
    description: str = Field(..., description="事件描述")

    # 涉及要素
    involved_characters: List[str] = Field(default_factory=list, description="涉及人物")
    involved_settings: List[str] = Field(default_factory=list, description="涉及设定")

    # 影响
    consequences: List[str] = Field(default_factory=list, description="事件后果")

    # 元数据
    created_at: datetime = Field(default_factory=datetime.now, description="创建时间")


class MemoryExtractionRequest(BaseModel):
    """记忆提取请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")
    chapter_id: str = Field(..., description="章节ID")

    # 提取配置
    extract_characters: bool = Field(default=True, description="是否提取人物信息")
    extract_world_settings: bool = Field(default=True, description="是否提取世界观设定")
    extract_plot: bool = Field(default=True, description="是否提取剧情信息")
    extract_suspense: bool = Field(default=True, description="是否提取悬念信息")
    extract_timeline: bool = Field(default=True, description="是否提取时间线事件")

    # 是否更新已有记忆
    update_existing: bool = Field(default=True, description="是否更新已有记忆")


class MemoryExtractionResponse(BaseModel):
    """记忆提取响应"""
    chapter_id: str = Field(..., description="章节ID")

    # 提取的记忆
    characters: List[CharacterMemory] = Field(default_factory=list, description="人物记忆")
    world_settings: List[WorldSettingMemory] = Field(default_factory=list, description="世界观记忆")
    plots: List[PlotMemory] = Field(default_factory=list, description="剧情记忆")
    suspenses: List[SuspenseMemory] = Field(default_factory=list, description="悬念记忆")
    timeline_events: List[TimelineEvent] = Field(default_factory=list, description="时间线事件")

    # 统计信息
    total_extracted: int = Field(..., description="总提取数量")
    new_created: int = Field(default=0, description="新建数量")
    updated: int = Field(default=0, description="更新数量")


class MemoryQueryRequest(BaseModel):
    """记忆查询请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")

    # 查询类型
    query_type: str = Field(..., description="查询类型：character/setting/plot/suspense/timeline")

    # 查询条件
    query: str = Field(..., description="查询内容")
    filters: Dict[str, Any] = Field(default_factory=dict, description="过滤条件")

    # 查询范围
    chapter_range: Optional[List[int]] = Field(None, description="章节范围 [start, end]")

    # 返回数量
    limit: int = Field(default=10, description="返回数量限制")


class MemoryQueryResponse(BaseModel):
    """记忆查询响应"""
    query_type: str = Field(..., description="查询类型")

    # 查询结果
    results: List[Dict[str, Any]] = Field(default_factory=list, description="查询结果")

    # 统计信息
    total_found: int = Field(..., description="找到总数")
    returned: int = Field(..., description="返回数量")


class ContinuityCheckRequest(BaseModel):
    """连续性检查请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")
    chapter_id: str = Field(..., description="章节ID")
    volume_number: Optional[int] = Field(1, description="卷号")
    chapter_number: Optional[int] = Field(None, description="章节号")
    chapter_title: Optional[str] = Field(None, description="章节标题")
    chapter_content: Optional[str] = Field(None, description="可选的章节正文；提供后无需从工作区读取")

    # 检查类型
    check_characters: bool = Field(default=True, description="检查人物连续性")
    check_settings: bool = Field(default=True, description="检查世界观连续性")
    check_timeline: bool = Field(default=True, description="检查时间线连续性")


class ContinuityIssue(BaseModel):
    """连续性问题"""
    issue_id: Optional[str] = Field(None, description="稳定问题ID")
    issue_type: str = Field(..., description="问题类型：character/setting/timeline")
    severity: str = Field(..., description="严重程度：critical/major/minor")

    # 问题描述
    title: str = Field(..., description="问题标题")
    description: str = Field(..., description="问题描述")

    # 冲突信息
    conflict_chapters: List[int] = Field(default_factory=list, description="冲突章节")
    conflict_details: str = Field(..., description="冲突详情")

    # 建议
    suggestion: str = Field(..., description="修改建议")


class ContinuityCheckResponse(BaseModel):
    """连续性检查响应"""
    chapter_id: str = Field(..., description="章节ID")

    # 检查结果
    has_issues: bool = Field(..., description="是否有问题")
    issues: List[ContinuityIssue] = Field(default_factory=list, description="问题列表")

    # 统计
    critical_count: int = Field(default=0, description="严重问题数")
    major_count: int = Field(default=0, description="重要问题数")
    minor_count: int = Field(default=0, description="轻微问题数")
