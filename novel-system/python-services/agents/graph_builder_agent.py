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
            "timeline": []
        }

        for memory_type in memories.keys():
            candidates = [
                project_memory_dir / f"{memory_type}.json",
                book_memory_dir / f"{memory_type}.json"
            ]
            memory_file = next((path for path in candidates if path.exists()), None)
            if memory_file:
                memories[memory_type] = await self._load_json_list(memory_file)

        return memories

    async def _build_graph(self, build_request: GraphBuildRequest, memories: Dict) -> KnowledgeGraph:
        """构建图谱"""
        graph = KnowledgeGraph(
            graph_id=f"graph_{uuid.uuid4().hex[:8]}",
            book_id=build_request.book_id
        )

        node_by_name: Dict[str, str] = {}

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

    def _as_list(self, value: Any) -> List[Any]:
        if value is None:
            return []
        if isinstance(value, list):
            return value
        return [value]
