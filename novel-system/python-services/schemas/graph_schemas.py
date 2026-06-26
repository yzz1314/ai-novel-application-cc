"""
知识图谱相关数据模型
"""
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from datetime import datetime


class GraphNode(BaseModel):
    """图谱节点基类"""
    node_id: str = Field(..., description="节点ID")
    node_type: str = Field(..., description="节点类型：character/location/organization/item/skill")
    name: str = Field(..., description="节点名称")

    # 属性
    properties: Dict[str, Any] = Field(default_factory=dict, description="节点属性")

    # 元数据
    first_mentioned: int = Field(..., description="首次提及章节")
    last_updated: int = Field(..., description="最后更新章节")
    created_at: datetime = Field(default_factory=datetime.now, description="创建时间")
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")


class GraphEdge(BaseModel):
    """图谱边（关系）"""
    edge_id: str = Field(..., description="边ID")
    edge_type: str = Field(..., description="关系类型")

    # 连接的节点
    source_id: str = Field(..., description="源节点ID")
    target_id: str = Field(..., description="目标节点ID")

    # 关系属性
    properties: Dict[str, Any] = Field(default_factory=dict, description="关系属性")
    weight: float = Field(default=1.0, description="关系权重")

    # 关系建立
    established_chapter: int = Field(..., description="关系建立章节")
    last_mentioned: int = Field(..., description="最后提及章节")

    # 元数据
    created_at: datetime = Field(default_factory=datetime.now, description="创建时间")
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")


class KnowledgeGraph(BaseModel):
    """知识图谱"""
    graph_id: str = Field(..., description="图谱ID")
    book_id: str = Field(..., description="书籍ID")

    # 节点和边
    nodes: List[GraphNode] = Field(default_factory=list, description="节点列表")
    edges: List[GraphEdge] = Field(default_factory=list, description="边列表")

    # 统计信息
    node_count: int = Field(default=0, description="节点数量")
    edge_count: int = Field(default=0, description="边数量")

    # 节点类型统计
    character_count: int = Field(default=0, description="人物节点数")
    location_count: int = Field(default=0, description="地点节点数")
    organization_count: int = Field(default=0, description="组织节点数")
    item_count: int = Field(default=0, description="物品节点数")
    skill_count: int = Field(default=0, description="技能节点数")
    statistics: Dict[str, Any] = Field(default_factory=dict, description="图谱统计与中心度摘要")
    analysis: Dict[str, Any] = Field(default_factory=dict, description="高级图谱分析结果")

    # 元数据
    version: str = Field(default="1.0.0", description="版本号")
    last_updated_chapter: int = Field(default=0, description="最后更新章节")
    created_at: datetime = Field(default_factory=datetime.now, description="创建时间")
    updated_at: datetime = Field(default_factory=datetime.now, description="更新时间")


class GraphBuildRequest(BaseModel):
    """图谱构建请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")

    # 构建配置
    use_memory_data: bool = Field(default=True, description="是否使用记忆数据")
    use_outline_data: bool = Field(default=True, description="是否使用大纲数据")

    # 节点类型
    include_characters: bool = Field(default=True, description="包含人物节点")
    include_locations: bool = Field(default=True, description="包含地点节点")
    include_organizations: bool = Field(default=True, description="包含组织节点")
    include_items: bool = Field(default=True, description="包含物品节点")
    include_skills: bool = Field(default=True, description="包含技能节点")

    # 关系类型
    include_character_relations: bool = Field(default=True, description="包含人物关系")
    include_spatial_relations: bool = Field(default=True, description="包含空间关系")
    include_ownership_relations: bool = Field(default=True, description="包含从属关系")

    # 是否增量构建
    incremental: bool = Field(default=True, description="是否增量构建")


class GraphBuildResponse(BaseModel):
    """图谱构建响应"""
    graph_id: str = Field(..., description="图谱ID")

    # 构建统计
    nodes_created: int = Field(..., description="新建节点数")
    nodes_updated: int = Field(..., description="更新节点数")
    edges_created: int = Field(..., description="新建边数")
    edges_updated: int = Field(..., description="更新边数")

    # 图谱统计
    total_nodes: int = Field(..., description="总节点数")
    total_edges: int = Field(..., description="总边数")

    # 图谱文件
    graph_file: str = Field(..., description="图谱文件路径")


class GraphQueryRequest(BaseModel):
    """图谱查询请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")

    # 查询类型
    query_type: str = Field(..., description="查询类型：node/edge/path/subgraph")

    # 查询条件
    node_type: Optional[str] = Field(None, description="节点类型过滤")
    node_name: Optional[str] = Field(None, description="节点名称")
    edge_type: Optional[str] = Field(None, description="边类型过滤")

    # 路径查询
    source_node: Optional[str] = Field(None, description="起始节点")
    target_node: Optional[str] = Field(None, description="目标节点")
    max_depth: int = Field(default=3, description="最大深度")

    # 子图查询
    center_node: Optional[str] = Field(None, description="中心节点")
    radius: int = Field(default=2, description="子图半径")

    # 返回限制
    limit: int = Field(default=20, description="返回数量限制")


class GraphQueryResponse(BaseModel):
    """图谱查询响应"""
    query_type: str = Field(..., description="查询类型")

    # 查询结果
    nodes: List[GraphNode] = Field(default_factory=list, description="节点结果")
    edges: List[GraphEdge] = Field(default_factory=list, description="边结果")
    paths: List[List[str]] = Field(default_factory=list, description="路径结果")

    # 统计
    total_found: int = Field(..., description="找到总数")
    returned: int = Field(..., description="返回数量")


class GraphExportRequest(BaseModel):
    """图谱导出请求"""
    project_id: str = Field(..., description="项目ID")
    book_id: str = Field(..., description="书籍ID")

    # 导出格式
    format: str = Field(..., description="导出格式：json/graphml/cytoscape/html")

    # 过滤条件
    node_types: Optional[List[str]] = Field(None, description="节点类型过滤")
    edge_types: Optional[List[str]] = Field(None, description="边类型过滤")
    chapter_range: Optional[List[int]] = Field(None, description="章节范围过滤")


class GraphExportResponse(BaseModel):
    """图谱导出响应"""
    format: str = Field(..., description="导出格式")
    file_path: str = Field(..., description="导出文件路径")
    node_count: int = Field(..., description="导出节点数")
    edge_count: int = Field(..., description="导出边数")


class RelationshipAnalysis(BaseModel):
    """关系分析结果"""
    node_id: str = Field(..., description="节点ID")
    node_name: str = Field(..., description="节点名称")

    # 关系统计
    total_relations: int = Field(..., description="总关系数")
    incoming_relations: int = Field(..., description="入边数")
    outgoing_relations: int = Field(..., description="出边数")

    # 关系分类
    relation_types: Dict[str, int] = Field(default_factory=dict, description="关系类型统计")

    # 重要关系
    top_relations: List[Dict[str, Any]] = Field(default_factory=list, description="Top关系")

    # 中心度
    degree_centrality: float = Field(default=0.0, description="度中心度")
    betweenness_centrality: float = Field(default=0.0, description="中介中心度")


class GraphStatistics(BaseModel):
    """图谱统计信息"""
    graph_id: str = Field(..., description="图谱ID")

    # 基本统计
    total_nodes: int = Field(..., description="总节点数")
    total_edges: int = Field(..., description="总边数")

    # 节点类型分布
    node_type_distribution: Dict[str, int] = Field(default_factory=dict, description="节点类型分布")

    # 边类型分布
    edge_type_distribution: Dict[str, int] = Field(default_factory=dict, description="边类型分布")

    # 图谱特征
    average_degree: float = Field(default=0.0, description="平均度")
    density: float = Field(default=0.0, description="密度")
    connected_components: int = Field(default=0, description="连通分量数")

    # Top节点
    top_nodes_by_degree: List[Dict[str, Any]] = Field(default_factory=list, description="度数Top节点")
    top_nodes_by_centrality: List[Dict[str, Any]] = Field(default_factory=list, description="中心度Top节点")
