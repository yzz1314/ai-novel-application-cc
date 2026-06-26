"""
知识图谱构建Agent（简化版）
从记忆数据构建实体关系图谱
"""
import json
import re
import uuid
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, List

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

            # 构建图谱
            graph = await self._build_graph(build_request, memories)

            # 保存图谱
            graph_files = await self._save_graph(build_request, graph)

            structured_output = {
                "graph_id": graph.graph_id,
                "total_nodes": graph.node_count,
                "total_edges": graph.edge_count,
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

        for event in memories.get("timeline", []):
            source_id = node_by_name.get(event.get("title", ""))
            if source_id:
                self._append_involvement_edges(graph, source_id, event, node_by_name, event.get("chapter", 1))

        graph.node_count = len(graph.nodes)
        graph.edge_count = len(graph.edges)
        if graph.nodes:
            graph.last_updated_chapter = max(node.last_updated for node in graph.nodes)

        return graph

    async def _save_graph(self, build_request: GraphBuildRequest, graph: KnowledgeGraph) -> List[Path]:
        """保存图谱"""
        project_root = Path(settings.PROJECT_BASE_PATH) / "projects" / build_request.project_id
        standard_dir = project_root / "graph"
        book_dir = project_root / "books" / build_request.book_id / "graph"

        files = [
            standard_dir / "graph.json",
            standard_dir / f"{build_request.book_id}_graph.json",
            book_dir / "knowledge_graph.json"
        ]

        for graph_file in files:
            graph_file.parent.mkdir(parents=True, exist_ok=True)
            with open(graph_file, 'w', encoding='utf-8') as f:
                json.dump(graph.dict(), f, ensure_ascii=False, indent=2, default=str)

        self.logger.info(f"Saved graph to {files[0]}")
        return files

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
