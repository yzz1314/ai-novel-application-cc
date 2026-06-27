"""
知识图谱构建Agent（简化版）
从记忆数据构建实体关系图谱
"""
import json
import re
import uuid
from collections import Counter, defaultdict, deque
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List, Optional, Set, Tuple

from agents.base import BaseAgent
from schemas.agent_request import AgentRequest
from schemas.agent_response import AgentResponse
from schemas.graph_schemas import (
    GraphNode, GraphEdge, KnowledgeGraph,
    GraphBuildRequest, GraphBuildResponse
)
from config import settings
from utils.logger import get_logger


class GraphBuilderAgent(BaseAgent):
    """知识图谱构建Agent"""

    def __init__(self):
        super().__init__("GraphBuilderAgent")
        self.supported_tasks = ["graph_build"]
        self.logger = get_logger("GraphBuilderAgent")

    async def run(self, request: AgentRequest) -> AgentResponse:
        """执行图谱构建任务"""
        self.metrics["start_time"] = datetime.now()

        try:
            await self.validate_request(request)
            build_request = GraphBuildRequest(**request.parameters)
            build_request.book_id = await self._resolve_book_id(
                build_request.project_id,
                build_request.book_id
            )

            self.logger.info(f"Building knowledge graph for book: {build_request.book_id}")

            # 加载记忆数据
            memories = await self._load_memories(build_request)

            previous_graph = await self._load_existing_graph(build_request) if build_request.incremental else None

            # 构建图谱
            graph = await self._build_graph(build_request, memories)
            if previous_graph:
                graph, incremental_summary = self._merge_incremental_graph(previous_graph, graph)
            else:
                incremental_summary = self._fresh_build_summary(graph, build_request.incremental)
            graph.analysis["incremental_build"] = incremental_summary

            # 保存图谱
            graph_files = await self._save_graph(build_request, graph)

            structured_output = {
                "graph_id": graph.graph_id,
                "total_nodes": graph.node_count,
                "total_edges": graph.edge_count,
                "incremental": build_request.incremental,
                "incremental_summary": incremental_summary,
                "nodes_created": incremental_summary.get("nodes_created", graph.node_count),
                "nodes_updated": incremental_summary.get("nodes_updated", 0),
                "edges_created": incremental_summary.get("edges_created", graph.edge_count),
                "edges_updated": incremental_summary.get("edges_updated", 0),
                "connected_components": graph.statistics.get("connected_components", 0),
                "top_nodes_by_centrality": graph.statistics.get("top_nodes_by_centrality", []),
                "graph_file": str(graph_files[0]),
                "graph_files": [str(path) for path in graph_files]
            }

            return self._build_response(
                request=request,
                status="success",
                output_refs=[str(path) for path in graph_files],
                structured_output=structured_output
            )

        except Exception as e:
            self.logger.error(f"Graph build failed: {str(e)}", exc_info=True)
            return self._build_response(
                request=request,
                status="failed",
                errors=[{"code": "GRAPH_BUILD_ERROR", "message": str(e), "retryable": True}]
            )

    async def _load_memories(self, build_request: GraphBuildRequest) -> Dict:
        """加载记忆数据"""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / build_request.project_id
        project_memory_dir = project_root / "memory"
        book_memory_dir = project_root / "books" / build_request.book_id / "memories"

        memories = {
            "characters": [],
            "world_settings": [],
            "plots": [],
            "suspenses": [],
            "timeline": [],
            "outline": {},
            "chapters": []
        }

        for memory_type in ["characters", "world_settings", "plots", "suspenses", "timeline"]:
            if not build_request.use_memory_data:
                break
            candidates = [
                project_memory_dir / f"{memory_type}.json",
                book_memory_dir / f"{memory_type}.json"
            ]
            memory_file = next((path for path in candidates if path.exists()), None)
            if memory_file:
                memories[memory_type] = await self._load_json_list(memory_file)

        if build_request.use_outline_data:
            memories["outline"] = await self._load_outline(build_request)

        memories["chapters"] = await self._load_chapters(build_request)

        return memories

    async def _build_graph(self, build_request: GraphBuildRequest, memories: Dict) -> KnowledgeGraph:
        """构建图谱"""
        graph = KnowledgeGraph(
            graph_id=f"graph_{uuid.uuid4().hex[:8]}",
            book_id=build_request.book_id
        )

        node_by_name: Dict[str, str] = {}
        outline = memories.get("outline") or {}

        # 添加人物节点
        for char in memories.get("characters", []):
            if not build_request.include_characters:
                continue
            name = char.get("name", "")
            node_id = char.get("character_id") or self._node_id("char", name)
            node = GraphNode(
                node_id=node_id,
                node_type="character",
                name=name,
                properties={
                    "role": char.get("role", ""),
                    "description": char.get("description", ""),
                    "aliases": char.get("aliases", []),
                    "current_status": char.get("current_status", {})
                },
                first_mentioned=char.get("first_mentioned", 1),
                last_updated=char.get("last_updated", 1)
            )
            graph.nodes.append(node)
            node_by_name[name] = node.node_id
            for alias in char.get("aliases", []):
                node_by_name[str(alias)] = node.node_id
            graph.character_count += 1

        # 添加世界观节点
        for setting in memories.get("world_settings", []):
            node_type = self._normalize_setting_type(setting.get("category", "location"))
            if not self._include_setting(build_request, node_type):
                continue
            name = setting.get("name", "")
            node_id = setting.get("setting_id") or self._node_id(node_type, name)
            node = GraphNode(
                node_id=node_id,
                node_type=node_type,
                name=name,
                properties={
                    "category": setting.get("category", ""),
                    "description": setting.get("description", ""),
                    "properties": setting.get("properties", {})
                },
                first_mentioned=setting.get("first_mentioned", 1),
                last_updated=setting.get("last_updated", 1)
            )
            graph.nodes.append(node)
            node_by_name[name] = node.node_id

            if node_type == "location":
                graph.location_count += 1
            elif node_type == "organization":
                graph.organization_count += 1
            elif node_type == "item":
                graph.item_count += 1
            elif node_type == "skill":
                graph.skill_count += 1

        # 添加剧情、伏笔和时间线节点，便于后续图谱检索参与上下文构建。
        for plot in memories.get("plots", []):
            title = plot.get("title", "")
            node = GraphNode(
                node_id=plot.get("plot_id") or self._node_id("plot", title),
                node_type="plot",
                name=title,
                properties={
                    "plot_type": plot.get("plot_type", ""),
                    "description": plot.get("description", ""),
                    "status": plot.get("status", "")
                },
                first_mentioned=plot.get("start_chapter", 1),
                last_updated=plot.get("end_chapter") or plot.get("start_chapter", 1)
            )
            graph.nodes.append(node)
            node_by_name[title] = node.node_id

        for suspense in memories.get("suspenses", []):
            title = suspense.get("title", "")
            node = GraphNode(
                node_id=suspense.get("suspense_id") or self._node_id("suspense", title),
                node_type="foreshadowing",
                name=title,
                properties={
                    "suspense_type": suspense.get("suspense_type", ""),
                    "description": suspense.get("description", ""),
                    "question": suspense.get("question", ""),
                    "status": suspense.get("status", "")
                },
                first_mentioned=suspense.get("set_chapter", 1),
                last_updated=suspense.get("resolved_chapter") or suspense.get("set_chapter", 1)
            )
            graph.nodes.append(node)
            node_by_name[title] = node.node_id

        for event in memories.get("timeline", []):
            title = event.get("title", "")
            node = GraphNode(
                node_id=event.get("event_id") or self._node_id("event", title),
                node_type="event",
                name=title,
                properties={
                    "event_type": event.get("event_type", ""),
                    "description": event.get("description", ""),
                    "consequences": event.get("consequences", [])
                },
                first_mentioned=event.get("chapter", 1),
                last_updated=event.get("chapter", 1)
            )
            graph.nodes.append(node)
            node_by_name[title] = node.node_id

        if outline:
            self._append_outline_entity_nodes(graph, build_request, outline, node_by_name)

        # 添加人物关系边
        for char in memories.get("characters", []):
            source_id = node_by_name.get(char.get("name", ""))
            if not source_id or not build_request.include_character_relations:
                continue
            # 添加关系边
            for rel_name, rel_type in char.get("relationships", {}).items():
                target_id = node_by_name.get(rel_name) or self._node_id("char", rel_name)
                edge = GraphEdge(
                    edge_id=self._edge_id(source_id, target_id, rel_type),
                    edge_type=rel_type,
                    source_id=source_id,
                    target_id=target_id,
                    properties={"target_name": rel_name},
                    established_chapter=char.get("first_mentioned", 1),
                    last_mentioned=char.get("last_updated", 1)
                )
                graph.edges.append(edge)

        # 添加设定关联边
        for setting in memories.get("world_settings", []):
            source_id = node_by_name.get(setting.get("name", ""))
            if not source_id:
                continue
            for char_name in setting.get("related_characters", []):
                target_id = node_by_name.get(char_name)
                if target_id:
                    graph.edges.append(self._edge(
                        source_id,
                        target_id,
                        "related_character",
                        setting.get("first_mentioned", 1),
                        setting.get("last_updated", 1)
                    ))
            for setting_name in setting.get("related_settings", []):
                target_id = node_by_name.get(setting_name)
                if target_id:
                    graph.edges.append(self._edge(
                        source_id,
                        target_id,
                        "related_setting",
                        setting.get("first_mentioned", 1),
                        setting.get("last_updated", 1)
                    ))

        # 添加剧情/时间线涉及关系
        for plot in memories.get("plots", []):
            source_id = node_by_name.get(plot.get("title", ""))
            if source_id:
                self._append_involvement_edges(graph, source_id, plot, node_by_name, plot.get("start_chapter", 1))

        for suspense in memories.get("suspenses", []):
            source_id = node_by_name.get(suspense.get("title", ""))
            if source_id:
                chapter = suspense.get("set_chapter", 1)
                self._append_involvement_edges(graph, source_id, suspense, node_by_name, chapter)
                for resolved_by in self._as_list(
                    suspense.get("resolved_by_plot")
                    or suspense.get("resolved_by")
                    or suspense.get("resolution_plot")
                ):
                    target_id = node_by_name.get(str(resolved_by))
                    if target_id:
                        graph.edges.append(self._edge(
                            source_id,
                            target_id,
                            "resolved_by",
                            chapter,
                            suspense.get("resolved_chapter") or chapter
                        ))

        for event in memories.get("timeline", []):
            source_id = node_by_name.get(event.get("title", ""))
            if source_id:
                self._append_involvement_edges(graph, source_id, event, node_by_name, event.get("chapter", 1))

        if outline:
            self._append_outline_edges(graph, outline, node_by_name)

        self._append_chapter_content_edges(graph, memories.get("chapters") or [], node_by_name)

        graph.node_count = len(graph.nodes)
        graph.edge_count = len(graph.edges)
        if graph.nodes:
            graph.last_updated_chapter = max(node.last_updated for node in graph.nodes)
        graph.statistics, graph.analysis = self._analyze_graph(graph)

        return graph

    async def _load_existing_graph(self, build_request: GraphBuildRequest) -> Optional[KnowledgeGraph]:
        for graph_file in self._existing_graph_paths(build_request):
            if not graph_file.exists():
                continue
            try:
                with open(graph_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                return KnowledgeGraph(**data)
            except Exception as exc:
                self.logger.warning(f"Skipping unreadable existing graph {graph_file}: {exc}")
        return None

    def _merge_incremental_graph(
        self,
        previous: KnowledgeGraph,
        current: KnowledgeGraph
    ) -> Tuple[KnowledgeGraph, Dict[str, Any]]:
        previous_nodes = {node.node_id: node for node in previous.nodes}
        previous_edges = {edge.edge_id: edge for edge in previous.edges}
        current_nodes = {node.node_id: node for node in current.nodes}
        current_edges = {edge.edge_id: edge for edge in current.edges}

        node_updates = 0
        edge_updates = 0
        merged_nodes: Dict[str, GraphNode] = dict(previous_nodes)
        merged_edges: Dict[str, GraphEdge] = dict(previous_edges)

        for node_id, node in current_nodes.items():
            previous_node = previous_nodes.get(node_id)
            if previous_node:
                node.created_at = previous_node.created_at
                if self._node_signature(previous_node) != self._node_signature(node):
                    node_updates += 1
            merged_nodes[node_id] = node

        for edge_id, edge in current_edges.items():
            previous_edge = previous_edges.get(edge_id)
            if previous_edge:
                edge.created_at = previous_edge.created_at
                if self._edge_signature(previous_edge) != self._edge_signature(edge):
                    edge_updates += 1
            merged_edges[edge_id] = edge

        merged = previous.model_copy(deep=True)
        merged.nodes = list(merged_nodes.values())
        merged.edges = list(merged_edges.values())
        merged.book_id = current.book_id
        merged.updated_at = datetime.now()
        merged.node_count = len(merged.nodes)
        merged.edge_count = len(merged.edges)
        merged.character_count = sum(1 for node in merged.nodes if node.node_type == "character")
        merged.location_count = sum(1 for node in merged.nodes if node.node_type == "location")
        merged.organization_count = sum(1 for node in merged.nodes if node.node_type == "organization")
        merged.item_count = sum(1 for node in merged.nodes if node.node_type == "item")
        merged.skill_count = sum(1 for node in merged.nodes if node.node_type == "skill")
        merged.last_updated_chapter = max((node.last_updated for node in merged.nodes), default=0)
        merged.statistics, merged.analysis = self._analyze_graph(merged)

        previous_only_nodes = set(previous_nodes) - set(current_nodes)
        previous_only_edges = set(previous_edges) - set(current_edges)
        return merged, {
            "enabled": True,
            "used_existing_graph": True,
            "previous_graph_id": previous.graph_id,
            "graph_id": merged.graph_id,
            "nodes_created": len(set(current_nodes) - set(previous_nodes)),
            "nodes_updated": node_updates,
            "nodes_unchanged": len(set(previous_nodes) & set(current_nodes)) - node_updates,
            "nodes_preserved_from_previous": len(previous_only_nodes),
            "edges_created": len(set(current_edges) - set(previous_edges)),
            "edges_updated": edge_updates,
            "edges_unchanged": len(set(previous_edges) & set(current_edges)) - edge_updates,
            "edges_preserved_from_previous": len(previous_only_edges),
            "previous_node_count": len(previous_nodes),
            "previous_edge_count": len(previous_edges),
            "current_generated_node_count": len(current_nodes),
            "current_generated_edge_count": len(current_edges),
            "merged_node_count": len(merged_nodes),
            "merged_edge_count": len(merged_edges),
            "preserved_node_ids": sorted(previous_only_nodes),
            "preserved_edge_ids": sorted(previous_only_edges),
            "updated_at": datetime.now().isoformat(),
        }

    def _fresh_build_summary(self, graph: KnowledgeGraph, incremental_requested: bool) -> Dict[str, Any]:
        return {
            "enabled": bool(incremental_requested),
            "used_existing_graph": False,
            "graph_id": graph.graph_id,
            "nodes_created": graph.node_count,
            "nodes_updated": 0,
            "nodes_unchanged": 0,
            "nodes_preserved_from_previous": 0,
            "edges_created": graph.edge_count,
            "edges_updated": 0,
            "edges_unchanged": 0,
            "edges_preserved_from_previous": 0,
            "previous_node_count": 0,
            "previous_edge_count": 0,
            "current_generated_node_count": graph.node_count,
            "current_generated_edge_count": graph.edge_count,
            "merged_node_count": graph.node_count,
            "merged_edge_count": graph.edge_count,
            "preserved_node_ids": [],
            "preserved_edge_ids": [],
            "updated_at": datetime.now().isoformat(),
        }

    def _node_signature(self, node: GraphNode) -> Dict[str, Any]:
        return node.model_dump(exclude={"created_at", "updated_at"})

    def _edge_signature(self, edge: GraphEdge) -> Dict[str, Any]:
        return edge.model_dump(exclude={"created_at", "updated_at"})

    def _analyze_graph(self, graph: KnowledgeGraph) -> tuple[Dict[str, Any], Dict[str, Any]]:
        nodes = graph.nodes
        edges = graph.edges
        node_by_id = {node.node_id: node for node in nodes}
        adjacency: Dict[str, Set[str]] = {node.node_id: set() for node in nodes}
        incoming = Counter()
        outgoing = Counter()
        relation_types: Dict[str, Counter] = defaultdict(Counter)
        edge_type_distribution = Counter()

        for edge in edges:
            if edge.source_id not in node_by_id or edge.target_id not in node_by_id:
                continue
            adjacency.setdefault(edge.source_id, set()).add(edge.target_id)
            adjacency.setdefault(edge.target_id, set()).add(edge.source_id)
            outgoing[edge.source_id] += 1
            incoming[edge.target_id] += 1
            relation_types[edge.source_id][edge.edge_type] += 1
            relation_types[edge.target_id][edge.edge_type] += 1
            edge_type_distribution[edge.edge_type] += 1

        degree = {node_id: len(neighbors) for node_id, neighbors in adjacency.items()}
        n = len(nodes)
        density = 0.0 if n < 2 else len(edges) / (n * (n - 1))
        average_degree = 0.0 if n == 0 else sum(degree.values()) / n
        components = self._connected_components(adjacency)
        degree_centrality = {
            node_id: (value / (n - 1) if n > 1 else 0.0)
            for node_id, value in degree.items()
        }
        betweenness = self._betweenness_centrality(adjacency)
        articulation_points = self._articulation_points(adjacency)

        top_degree = self._top_node_scores(node_by_id, degree, "degree", limit=10)
        top_centrality = self._top_node_scores(node_by_id, degree_centrality, "degreeCentrality", limit=10)
        top_betweenness = self._top_node_scores(node_by_id, betweenness, "betweennessCentrality", limit=10)
        bridge_nodes = [
            self._node_summary(node_by_id[node_id], {
                "degree": degree.get(node_id, 0),
                "degreeCentrality": round(degree_centrality.get(node_id, 0.0), 6),
                "betweennessCentrality": round(betweenness.get(node_id, 0.0), 6),
            })
            for node_id in sorted(
                articulation_points,
                key=lambda item: (betweenness.get(item, 0.0), degree.get(item, 0)),
                reverse=True
            )
            if node_id in node_by_id
        ][:10]

        relationship_analysis = []
        for node in nodes:
            node_id = node.node_id
            rel_types = dict(relation_types.get(node_id, Counter()))
            top_relations = self._top_relations(node_id, edges, node_by_id)
            relationship_analysis.append({
                "node_id": node_id,
                "node_name": node.name,
                "node_type": node.node_type,
                "total_relations": incoming[node_id] + outgoing[node_id],
                "incoming_relations": incoming[node_id],
                "outgoing_relations": outgoing[node_id],
                "relation_types": rel_types,
                "top_relations": top_relations,
                "degree_centrality": round(degree_centrality.get(node_id, 0.0), 6),
                "betweenness_centrality": round(betweenness.get(node_id, 0.0), 6),
            })
        relationship_analysis.sort(
            key=lambda item: (item["degree_centrality"], item["betweenness_centrality"], item["total_relations"]),
            reverse=True
        )

        key_paths = self._key_paths(node_by_id, adjacency, top_centrality[:6])
        isolated_nodes = [
            self._node_summary(node, {"degree": degree.get(node.node_id, 0)})
            for node in nodes
            if degree.get(node.node_id, 0) == 0
        ][:20]

        statistics = {
            "total_nodes": len(nodes),
            "total_edges": len(edges),
            "node_type_distribution": dict(Counter(node.node_type for node in nodes)),
            "edge_type_distribution": dict(edge_type_distribution),
            "average_degree": round(average_degree, 4),
            "density": round(density, 6),
            "connected_components": len(components),
            "largest_component_size": max((len(component) for component in components), default=0),
            "top_nodes_by_degree": top_degree,
            "top_nodes_by_centrality": top_centrality,
            "top_nodes_by_betweenness": top_betweenness,
        }
        analysis = {
            "generated_at": datetime.now().isoformat(),
            "component_summary": [
                {
                    "component_id": f"component_{index + 1}",
                    "size": len(component),
                    "nodes": [self._node_summary(node_by_id[node_id]) for node_id in component[:12] if node_id in node_by_id],
                }
                for index, component in enumerate(components[:10])
            ],
            "bridge_nodes": bridge_nodes,
            "isolated_nodes": isolated_nodes,
            "relationship_analysis": relationship_analysis[:20],
            "key_paths": key_paths,
            "warnings": self._graph_warnings(nodes, edges, components, isolated_nodes),
        }
        return statistics, analysis

    def _connected_components(self, adjacency: Dict[str, Set[str]]) -> List[List[str]]:
        visited: Set[str] = set()
        components: List[List[str]] = []
        for node_id in adjacency:
            if node_id in visited:
                continue
            queue = deque([node_id])
            visited.add(node_id)
            component = []
            while queue:
                current = queue.popleft()
                component.append(current)
                for neighbor in adjacency.get(current, set()):
                    if neighbor not in visited:
                        visited.add(neighbor)
                        queue.append(neighbor)
            components.append(component)
        components.sort(key=len, reverse=True)
        return components

    def _betweenness_centrality(self, adjacency: Dict[str, Set[str]]) -> Dict[str, float]:
        nodes = list(adjacency.keys())
        scores = {node_id: 0.0 for node_id in nodes}
        if len(nodes) < 3:
            return scores

        for source in nodes:
            stack: List[str] = []
            predecessors: Dict[str, List[str]] = {node_id: [] for node_id in nodes}
            sigma = dict.fromkeys(nodes, 0.0)
            sigma[source] = 1.0
            distance = dict.fromkeys(nodes, -1)
            distance[source] = 0
            queue = deque([source])

            while queue:
                vertex = queue.popleft()
                stack.append(vertex)
                for neighbor in adjacency.get(vertex, set()):
                    if distance[neighbor] < 0:
                        queue.append(neighbor)
                        distance[neighbor] = distance[vertex] + 1
                    if distance[neighbor] == distance[vertex] + 1:
                        sigma[neighbor] += sigma[vertex]
                        predecessors[neighbor].append(vertex)

            dependency = dict.fromkeys(nodes, 0.0)
            while stack:
                vertex = stack.pop()
                for predecessor in predecessors[vertex]:
                    if sigma[vertex]:
                        dependency[predecessor] += (sigma[predecessor] / sigma[vertex]) * (1 + dependency[vertex])
                if vertex != source:
                    scores[vertex] += dependency[vertex]

        scale = 1 / ((len(nodes) - 1) * (len(nodes) - 2)) if len(nodes) > 2 else 1.0
        return {node_id: value * scale for node_id, value in scores.items()}

    def _articulation_points(self, adjacency: Dict[str, Set[str]]) -> Set[str]:
        visited: Set[str] = set()
        discovery: Dict[str, int] = {}
        low: Dict[str, int] = {}
        parent: Dict[str, Optional[str]] = {}
        points: Set[str] = set()
        time = 0

        def dfs(node_id: str):
            nonlocal time
            visited.add(node_id)
            discovery[node_id] = time
            low[node_id] = time
            time += 1
            children = 0
            for neighbor in adjacency.get(node_id, set()):
                if neighbor not in visited:
                    parent[neighbor] = node_id
                    children += 1
                    dfs(neighbor)
                    low[node_id] = min(low[node_id], low[neighbor])
                    if parent.get(node_id) is None and children > 1:
                        points.add(node_id)
                    if parent.get(node_id) is not None and low[neighbor] >= discovery[node_id]:
                        points.add(node_id)
                elif neighbor != parent.get(node_id):
                    low[node_id] = min(low[node_id], discovery[neighbor])

        for node_id in adjacency:
            if node_id not in visited:
                parent[node_id] = None
                dfs(node_id)
        return points

    def _top_node_scores(
        self,
        node_by_id: Dict[str, GraphNode],
        scores: Dict[str, Any],
        score_key: str,
        limit: int = 10
    ) -> List[Dict[str, Any]]:
        items = []
        for node_id, score in scores.items():
            node = node_by_id.get(node_id)
            if not node:
                continue
            value = round(float(score), 6) if isinstance(score, float) else score
            items.append(self._node_summary(node, {score_key: value}))
        return sorted(items, key=lambda item: item.get(score_key, 0), reverse=True)[:limit]

    def _top_relations(
        self,
        node_id: str,
        edges: List[GraphEdge],
        node_by_id: Dict[str, GraphNode]
    ) -> List[Dict[str, Any]]:
        relations = []
        for edge in edges:
            other_id = None
            direction = None
            if edge.source_id == node_id:
                other_id = edge.target_id
                direction = "outgoing"
            elif edge.target_id == node_id:
                other_id = edge.source_id
                direction = "incoming"
            if not other_id:
                continue
            other = node_by_id.get(other_id)
            relations.append({
                "edge_id": edge.edge_id,
                "edge_type": edge.edge_type,
                "direction": direction,
                "node_id": other_id,
                "node_name": other.name if other else other_id,
                "node_type": other.node_type if other else "",
                "weight": edge.weight,
            })
        return sorted(relations, key=lambda item: item["weight"], reverse=True)[:8]

    def _key_paths(
        self,
        node_by_id: Dict[str, GraphNode],
        adjacency: Dict[str, Set[str]],
        top_nodes: List[Dict[str, Any]]
    ) -> List[Dict[str, Any]]:
        node_ids = [item["node_id"] for item in top_nodes if item.get("node_id") in node_by_id]
        paths = []
        for index, source in enumerate(node_ids):
            for target in node_ids[index + 1:]:
                path = self._shortest_path(adjacency, source, target, max_depth=5)
                if path and len(path) > 1:
                    paths.append({
                        "source": self._node_summary(node_by_id[source]),
                        "target": self._node_summary(node_by_id[target]),
                        "length": len(path) - 1,
                        "path": [self._node_summary(node_by_id[node_id]) for node_id in path if node_id in node_by_id],
                    })
        return sorted(paths, key=lambda item: item["length"])[:10]

    def _shortest_path(
        self,
        adjacency: Dict[str, Set[str]],
        source: str,
        target: str,
        max_depth: int
    ) -> Optional[List[str]]:
        queue = deque([[source]])
        visited = {source}
        while queue:
            path = queue.popleft()
            current = path[-1]
            if current == target:
                return path
            if len(path) > max_depth:
                continue
            for neighbor in adjacency.get(current, set()):
                if neighbor not in visited:
                    visited.add(neighbor)
                    queue.append(path + [neighbor])
        return None

    def _graph_warnings(
        self,
        nodes: List[GraphNode],
        edges: List[GraphEdge],
        components: List[List[str]],
        isolated_nodes: List[Dict[str, Any]]
    ) -> List[Dict[str, Any]]:
        warnings = []
        if nodes and not edges:
            warnings.append({
                "code": "GRAPH_HAS_NO_EDGES",
                "severity": "warning",
                "message": "图谱已有节点但缺少关系边，建议补充人物关系、剧情涉及角色或设定关联。",
            })
        if len(components) > 1:
            warnings.append({
                "code": "GRAPH_DISCONNECTED",
                "severity": "info",
                "message": f"图谱包含 {len(components)} 个连通分量，可能存在孤立剧情线或未关联设定。",
            })
        if isolated_nodes:
            warnings.append({
                "code": "GRAPH_HAS_ISOLATED_NODES",
                "severity": "info",
                "message": f"发现 {len(isolated_nodes)} 个孤立节点，可检查记忆抽取是否缺少关系。",
            })
        return warnings

    def _node_summary(self, node: GraphNode, extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        item = {
            "node_id": node.node_id,
            "name": node.name,
            "node_type": node.node_type,
        }
        if extra:
            item.update(extra)
        return item

    async def _save_graph(self, build_request: GraphBuildRequest, graph: KnowledgeGraph) -> List[Path]:
        """保存图谱"""
        files = self._existing_graph_paths(build_request)

        for graph_file in files:
            graph_file.parent.mkdir(parents=True, exist_ok=True)
            with open(graph_file, 'w', encoding='utf-8') as f:
                json.dump(graph.model_dump(), f, ensure_ascii=False, indent=2, default=str)

        self.logger.info(f"Saved graph to {files[0]}")
        return files

    def _existing_graph_paths(self, build_request: GraphBuildRequest) -> List[Path]:
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / build_request.project_id
        standard_dir = project_root / "graph"
        book_dir = project_root / "books" / build_request.book_id / "graph"
        return [
            standard_dir / "graph.json",
            standard_dir / f"{build_request.book_id}_graph.json",
            book_dir / "knowledge_graph.json",
        ]

    async def _resolve_book_id(self, project_id: str, book_id: str) -> str:
        """将 default 书籍解析为最近大纲对应的真实 book_id。"""
        if book_id and book_id != "default":
            return book_id

        outline_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "novel" / "outline"
        legacy_outline_dir = Path(settings.PROJECT_BASE_PATH) / "projects" / project_id / "outlines"
        outline_files = []
        for directory in [outline_dir, legacy_outline_dir]:
            if directory.exists():
                outline_files.extend(directory.glob("*_outline.json"))

        if not outline_files:
            return book_id or "default"

        latest = max(outline_files, key=lambda path: path.stat().st_mtime)
        try:
            with open(latest, 'r', encoding='utf-8') as f:
                outline = json.load(f)
            return outline.get("book_id") or latest.name.replace("_outline.json", "")
        except Exception:
            return latest.name.replace("_outline.json", "")

    async def _load_json_list(self, path: Path) -> List[Dict[str, Any]]:
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return data if isinstance(data, list) else []

    async def _load_outline(self, build_request: GraphBuildRequest) -> Dict[str, Any]:
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / build_request.project_id
        outline_dirs = [
            project_root / "novel" / "outline",
            project_root / "outlines",
        ]
        candidates: List[Path] = []
        if build_request.book_id:
            candidates.extend(directory / f"{build_request.book_id}_outline.json" for directory in outline_dirs)

        for candidate in candidates:
            if candidate.exists():
                with open(candidate, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                return data if isinstance(data, dict) else {}

        outline_files: List[Path] = []
        for directory in outline_dirs:
            if directory.exists():
                outline_files.extend(directory.glob("*_outline.json"))
        if not outline_files:
            return {}

        latest = max(outline_files, key=lambda path: path.stat().st_mtime)
        with open(latest, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}

    async def _load_chapters(self, build_request: GraphBuildRequest) -> List[Dict[str, Any]]:
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / build_request.project_id
        chapter_roots = [
            project_root / "novel" / "chapters" / "final" / build_request.book_id,
            project_root / "novel" / "chapters" / "drafts" / build_request.book_id,
            project_root / "books" / build_request.book_id,
        ]
        chapters_by_key: Dict[Tuple[int, int], Dict[str, Any]] = {}

        for stage_index, root in enumerate(chapter_roots):
            if not root.exists():
                continue
            chapter_files = list(root.glob("volume_*/chapter_*.json"))
            chapter_files.extend(root.glob("volume_*/chapters/chapter_*.json"))
            for chapter_file in sorted(chapter_files):
                try:
                    with open(chapter_file, 'r', encoding='utf-8') as f:
                        chapter = json.load(f)
                except Exception as exc:
                    self.logger.warning(f"Skipping unreadable chapter {chapter_file}: {exc}")
                    continue
                if not isinstance(chapter, dict):
                    continue
                volume_number = self._safe_int(chapter.get("volume_number"), self._volume_from_path(chapter_file))
                chapter_number = self._safe_int(chapter.get("chapter_number"), self._chapter_from_path(chapter_file))
                if chapter_number <= 0:
                    continue
                chapter["volume_number"] = volume_number
                chapter["chapter_number"] = chapter_number
                chapter["source_stage"] = "final" if stage_index == 0 else "draft"
                chapter["source_path"] = str(chapter_file)
                chapters_by_key.setdefault((volume_number, chapter_number), chapter)

        return [
            chapters_by_key[key]
            for key in sorted(chapters_by_key.keys())
        ]

    def _node_id(self, prefix: str, name: str) -> str:
        slug = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff_-]+", "_", str(name)).strip("_")
        if not slug:
            slug = uuid.uuid4().hex[:8]
        return f"{prefix}_{slug}"

    def _edge_id(self, source_id: str, target_id: str, edge_type: str) -> str:
        raw = f"{source_id}_{edge_type}_{target_id}"
        slug = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff_-]+", "_", raw).strip("_")
        return f"edge_{slug[:120]}"

    def _edge(self, source_id: str, target_id: str, edge_type: str, chapter: int, last_mentioned: int) -> GraphEdge:
        return GraphEdge(
            edge_id=self._edge_id(source_id, target_id, edge_type),
            edge_type=edge_type,
            source_id=source_id,
            target_id=target_id,
            established_chapter=chapter,
            last_mentioned=last_mentioned
        )

    def _normalize_setting_type(self, category: str) -> str:
        normalized = str(category).strip().lower()
        return {
            "地点": "location",
            "location": "location",
            "组织": "organization",
            "势力": "organization",
            "organization": "organization",
            "物品": "item",
            "道具": "item",
            "item": "item",
            "技能": "skill",
            "功法": "skill",
            "skill": "skill",
            "规则": "rule",
            "rule": "rule"
        }.get(normalized, normalized or "setting")

    def _include_setting(self, build_request: GraphBuildRequest, node_type: str) -> bool:
        if node_type == "location":
            return build_request.include_locations
        if node_type == "organization":
            return build_request.include_organizations
        if node_type == "item":
            return build_request.include_items
        if node_type == "skill":
            return build_request.include_skills
        return True

    def _append_involvement_edges(
        self,
        graph: KnowledgeGraph,
        source_id: str,
        data: Dict[str, Any],
        node_by_name: Dict[str, str],
        chapter: int
    ):
        for char_name in data.get("involved_characters", []):
            target_id = node_by_name.get(char_name)
            if target_id:
                graph.edges.append(self._edge(source_id, target_id, "involves_character", chapter, chapter))
        for setting_name in data.get("involved_settings", []):
            target_id = node_by_name.get(setting_name)
            if target_id:
                graph.edges.append(self._edge(source_id, target_id, "involves_setting", chapter, chapter))

    def _append_outline_entity_nodes(
        self,
        graph: KnowledgeGraph,
        build_request: GraphBuildRequest,
        outline: Dict[str, Any],
        node_by_name: Dict[str, str]
    ):
        for character in outline.get("characters") or []:
            if not build_request.include_characters or not isinstance(character, dict):
                continue
            name = str(character.get("name") or "").strip()
            if not name or name in node_by_name:
                continue
            first_chapter = self._safe_int(character.get("introduction_chapter"), 1)
            node = GraphNode(
                node_id=self._node_id("char", name),
                node_type="character",
                name=name,
                properties={
                    "role": character.get("role", ""),
                    "description": character.get("description", ""),
                    "attributes": character.get("attributes", {}),
                    "relationships": character.get("relationships", []),
                    "source": "outline"
                },
                first_mentioned=first_chapter,
                last_updated=first_chapter
            )
            graph.nodes.append(node)
            node_by_name[name] = node.node_id
            graph.character_count += 1

        for setting in outline.get("world_settings") or []:
            if not isinstance(setting, dict):
                continue
            name = str(setting.get("name") or "").strip()
            node_type = self._normalize_setting_type(setting.get("category", "location"))
            if not name or name in node_by_name or not self._include_setting(build_request, node_type):
                continue
            node = GraphNode(
                node_id=self._node_id(node_type, name),
                node_type=node_type,
                name=name,
                properties={
                    "category": setting.get("category", ""),
                    "description": setting.get("description", ""),
                    "related_entities": setting.get("related_entities", []),
                    "source": "outline"
                },
                first_mentioned=1,
                last_updated=1
            )
            graph.nodes.append(node)
            node_by_name[name] = node.node_id
            if node_type == "location":
                graph.location_count += 1
            elif node_type == "organization":
                graph.organization_count += 1
            elif node_type == "item":
                graph.item_count += 1
            elif node_type == "skill":
                graph.skill_count += 1

        for suspense_name in self._outline_suspense_names(outline):
            if suspense_name in node_by_name:
                continue
            node = GraphNode(
                node_id=self._node_id("suspense", suspense_name),
                node_type="foreshadowing",
                name=suspense_name,
                properties={
                    "status": "outlined",
                    "source": "outline"
                },
                first_mentioned=1,
                last_updated=1
            )
            graph.nodes.append(node)
            node_by_name[suspense_name] = node.node_id

    def _append_outline_edges(
        self,
        graph: KnowledgeGraph,
        outline: Dict[str, Any],
        node_by_name: Dict[str, str]
    ):
        names_by_type = self._node_names_by_type(graph)

        for setting in outline.get("world_settings") or []:
            if not isinstance(setting, dict):
                continue
            source_id = node_by_name.get(str(setting.get("name") or "").strip())
            if not source_id:
                continue
            for entity_name in self._string_values(setting.get("related_entities")):
                target_id = node_by_name.get(entity_name)
                if target_id and target_id != source_id:
                    graph.edges.append(self._edge(source_id, target_id, "related_entity", 1, 1))

        previous_chapter_id: Optional[str] = None
        for volume_index, volume in enumerate(outline.get("volumes") or [], start=1):
            if not isinstance(volume, dict):
                continue
            volume_number = self._safe_int(volume.get("volume_number"), volume_index)
            for chapter_index, chapter in enumerate(volume.get("chapters") or [], start=1):
                if not isinstance(chapter, dict):
                    continue
                chapter_number = self._safe_int(chapter.get("chapter_number"), chapter_index)
                chapter_id = self._outline_chapter_node_id(volume_number, chapter_number)
                chapter_title = str(chapter.get("chapter_title") or f"第{chapter_number}章").strip()
                graph.nodes.append(GraphNode(
                    node_id=chapter_id,
                    node_type="chapter_outline",
                    name=chapter_title,
                    properties={
                        "source": "outline",
                        "volume_number": volume_number,
                        "chapter_number": chapter_number,
                        "plot_goal": chapter.get("plot_goal", ""),
                        "character_development": chapter.get("character_development", ""),
                        "info_reveal": chapter.get("info_reveal", ""),
                        "conflict": chapter.get("conflict", ""),
                        "appeal_point": chapter.get("appeal_point", ""),
                        "suspense": chapter.get("suspense", ""),
                        "core_goal": chapter.get("core_goal", ""),
                        "must_write": chapter.get("must_write", []),
                        "allowed_progress": chapter.get("allowed_progress", []),
                        "must_not_write": chapter.get("must_not_write", []),
                        "reserved_for_future": chapter.get("reserved_for_future", {}),
                        "stop_point": chapter.get("stop_point", ""),
                        "ending_hook": chapter.get("ending_hook", "")
                    },
                    first_mentioned=chapter_number,
                    last_updated=chapter_number
                ))
                node_by_name[chapter_title] = chapter_id

                if previous_chapter_id:
                    graph.edges.append(self._edge(previous_chapter_id, chapter_id, "next_chapter", chapter_number, chapter_number))
                previous_chapter_id = chapter_id

                for character_name in self._match_known_names(chapter, names_by_type.get("character", set())):
                    target_id = node_by_name.get(character_name)
                    if target_id:
                        graph.edges.append(self._edge(chapter_id, target_id, "involves_character", chapter_number, chapter_number))

                for setting_name in self._match_known_names(chapter, names_by_type.get("setting", set())):
                    target_id = node_by_name.get(setting_name)
                    if target_id:
                        graph.edges.append(self._edge(chapter_id, target_id, "involves_setting", chapter_number, chapter_number))

                for suspense_name in self._match_known_names(chapter, names_by_type.get("foreshadowing", set())):
                    target_id = node_by_name.get(suspense_name)
                    if target_id:
                        graph.edges.append(self._edge(chapter_id, target_id, "sets_up_foreshadowing", chapter_number, chapter_number))

                for plot_name in self._match_known_names(chapter, names_by_type.get("plot", set())):
                    target_id = node_by_name.get(plot_name)
                    if target_id:
                        graph.edges.append(self._edge(chapter_id, target_id, "advances_plot", chapter_number, chapter_number))

    def _append_chapter_content_edges(
        self,
        graph: KnowledgeGraph,
        chapters: List[Dict[str, Any]],
        node_by_name: Dict[str, str]
    ):
        if not chapters:
            return

        names_by_type = self._node_names_by_type(graph)
        previous_chapter_id: Optional[str] = None
        for chapter in chapters:
            if not isinstance(chapter, dict):
                continue
            volume_number = self._safe_int(chapter.get("volume_number"), 1)
            chapter_number = self._safe_int(chapter.get("chapter_number"), 1)
            content = str(chapter.get("content") or "").strip()
            if not content:
                continue

            chapter_id = self._chapter_content_node_id(volume_number, chapter_number)
            chapter_title = str(chapter.get("chapter_title") or f"第{chapter_number}章正文").strip()
            graph.nodes.append(GraphNode(
                node_id=chapter_id,
                node_type="chapter_content",
                name=chapter_title,
                properties={
                    "source": "chapter_content",
                    "source_stage": chapter.get("source_stage", "draft"),
                    "source_path": chapter.get("source_path", ""),
                    "book_id": chapter.get("book_id", ""),
                    "volume_number": volume_number,
                    "chapter_number": chapter_number,
                    "word_count": chapter.get("word_count") or len(content),
                    "review_status": chapter.get("review_status", ""),
                    "quality_score": chapter.get("quality_score", 0),
                    "version": chapter.get("version", 1),
                    "content_excerpt": content[:240]
                },
                first_mentioned=chapter_number,
                last_updated=chapter_number
            ))

            outline_id = self._outline_chapter_node_id(volume_number, chapter_number)
            if any(node.node_id == outline_id for node in graph.nodes):
                graph.edges.append(self._edge(chapter_id, outline_id, "implements_outline", chapter_number, chapter_number))

            if previous_chapter_id:
                graph.edges.append(self._edge(previous_chapter_id, chapter_id, "next_chapter", chapter_number, chapter_number))
            previous_chapter_id = chapter_id

            for character_name in self._match_known_names(content, names_by_type.get("character", set())):
                target_id = node_by_name.get(character_name)
                if target_id:
                    graph.edges.append(self._edge(chapter_id, target_id, "mentions_character", chapter_number, chapter_number))

            for setting_name in self._match_known_names(content, names_by_type.get("setting", set())):
                target_id = node_by_name.get(setting_name)
                if target_id:
                    graph.edges.append(self._edge(chapter_id, target_id, "mentions_setting", chapter_number, chapter_number))

            for suspense_name in self._match_known_names(content, names_by_type.get("foreshadowing", set())):
                target_id = node_by_name.get(suspense_name)
                if target_id:
                    graph.edges.append(self._edge(chapter_id, target_id, "mentions_foreshadowing", chapter_number, chapter_number))

            for plot_name in self._match_known_names(content, names_by_type.get("plot", set())):
                target_id = node_by_name.get(plot_name)
                if target_id:
                    graph.edges.append(self._edge(chapter_id, target_id, "mentions_plot", chapter_number, chapter_number))

    def _node_names_by_type(self, graph: KnowledgeGraph) -> Dict[str, Set[str]]:
        names_by_type: Dict[str, Set[str]] = defaultdict(set)
        setting_types = {"location", "organization", "item", "skill", "rule", "setting"}
        for node in graph.nodes:
            if not node.name:
                continue
            names_by_type[node.node_type].add(node.name)
            if node.node_type in setting_types:
                names_by_type["setting"].add(node.name)
            if node.node_type == "character":
                for alias in node.properties.get("aliases", []):
                    alias_name = str(alias).strip()
                    if alias_name:
                        names_by_type["character"].add(alias_name)
        return names_by_type

    def _outline_suspense_names(self, outline: Dict[str, Any]) -> List[str]:
        names: List[str] = []
        names.extend(self._string_values(outline.get("long_term_suspense")))
        for volume in outline.get("volumes") or []:
            if not isinstance(volume, dict):
                continue
            for key in ["new_suspense", "resolved_suspense", "ongoing_suspense"]:
                names.extend(self._string_values(volume.get(key)))
        return self._unique_non_empty(names)

    def _outline_chapter_node_id(self, volume_number: int, chapter_number: int) -> str:
        return f"outline_chapter_{volume_number}_{chapter_number}"

    def _chapter_content_node_id(self, volume_number: int, chapter_number: int) -> str:
        return f"chapter_content_{volume_number}_{chapter_number}"

    def _match_known_names(self, data: Any, known_names: Set[str]) -> Set[str]:
        text = " ".join(self._string_values(data))
        matched: Set[str] = set()
        for name in known_names:
            normalized = str(name).strip()
            if len(normalized) >= 2 and normalized in text:
                matched.add(normalized)
        return matched

    def _string_values(self, value: Any) -> List[str]:
        if value is None:
            return []
        if isinstance(value, str):
            stripped = value.strip()
            return [stripped] if stripped else []
        if isinstance(value, dict):
            values: List[str] = []
            for item in value.values():
                values.extend(self._string_values(item))
            return values
        if isinstance(value, (list, tuple, set)):
            values: List[str] = []
            for item in value:
                values.extend(self._string_values(item))
            return values
        text = str(value).strip()
        return [text] if text else []

    def _unique_non_empty(self, values: List[str]) -> List[str]:
        result: List[str] = []
        seen: Set[str] = set()
        for value in values:
            normalized = str(value).strip()
            if normalized and normalized not in seen:
                seen.add(normalized)
                result.append(normalized)
        return result

    def _safe_int(self, value: Any, default: int) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return default

    def _volume_from_path(self, path: Path) -> int:
        match = re.search(r"volume_(\d+)", str(path))
        return int(match.group(1)) if match else 1

    def _chapter_from_path(self, path: Path) -> int:
        match = re.search(r"chapter_(\d+)", path.name)
        return int(match.group(1)) if match else 0

    def _as_list(self, value: Any) -> List[Any]:
        if value is None:
            return []
        if isinstance(value, list):
            return value
        return [value]
