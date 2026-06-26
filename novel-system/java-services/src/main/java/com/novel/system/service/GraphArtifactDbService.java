package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.GraphArtifact;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.GraphArtifactRepository;
import com.novel.system.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphArtifactDbService {

    private final ProjectService projectService;
    private final GraphArtifactRepository graphArtifactRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Transactional
    public Map<String, Object> syncGraphFromWorkspace(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        String bookId = resolveBookId(request);
        Path graphFile = resolveGraphFile(projectId, bookId);
        if (graphFile == null) {
            throw new ResourceNotFoundException("图谱文件不存在，请先构建图谱: " + projectId);
        }

        Map<String, Object> graph = readJsonMap(graphFile);
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(graph.get("edges"));
        Map<String, Object> statistics = statistics(nodes, edges, mapOf(graph.get("statistics")));

        GraphArtifact entity = graphArtifactRepository
            .findByProjectIdAndBookId(projectId, bookId)
            .orElseGet(GraphArtifact::new);
        entity.setId(projectId + ":" + bookId);
        entity.setProjectId(projectId);
        entity.setBookId(bookId);
        entity.setGraphId(stringValue(graph.get("graph_id"), null));
        entity.setStatus(nodes.isEmpty() && edges.isEmpty() ? "empty" : "synced");
        entity.setNodeCount(nodes.size());
        entity.setEdgeCount(edges.size());
        entity.setCharacterCount(countByType(nodes, "character"));
        entity.setLocationCount(countByType(nodes, "location"));
        entity.setOrganizationCount(countByType(nodes, "organization"));
        entity.setItemCount(countByType(nodes, "item"));
        entity.setGraphPath(relative(projectId, graphFile));
        entity.setGraphJson(graph);
        entity.setNodes(nodes);
        entity.setEdges(edges);
        entity.setStatistics(statistics);
        entity.setNodeTypeDistribution(asObjectMap(statistics.get("nodeTypeDistribution")));
        entity.setEdgeTypeDistribution(asObjectMap(statistics.get("edgeTypeDistribution")));
        entity.setTopNodesByDegree(listOfMaps(statistics.get("topNodesByDegree")));
        entity.setAverageDegree(doubleValue(statistics.get("averageDegree")));
        entity.setDensity(doubleValue(statistics.get("density")));
        entity.setLatestTasks(latestGraphTasks(projectId));
        entity.setGraphMetadata(metadata(projectId, bookId, graphFile, nodes, edges));
        entity.setSyncedAt(LocalDateTime.now());

        GraphArtifact saved = graphArtifactRepository.save(entity);
        log.info("Synced graph artifact to DB: project={}, book={}, nodes={}, edges={}",
            projectId, bookId, saved.getNodeCount(), saved.getEdgeCount());
        return response(saved);
    }

    public Map<String, Object> getGraph(String projectId, String bookId) {
        projectService.getProject(projectId);
        GraphArtifact artifact = graphArtifactRepository
            .findByProjectIdAndBookId(projectId, resolveBookIdValue(bookId))
            .or(() -> graphArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(projectId).stream().findFirst())
            .orElseThrow(() -> new ResourceNotFoundException("数据库图谱不存在，请先同步: " + projectId));
        return response(artifact);
    }

    public List<Map<String, Object>> listGraphs(String projectId) {
        projectService.getProject(projectId);
        return graphArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(projectId)
            .stream()
            .map(this::summary)
            .toList();
    }

    private Map<String, Object> response(GraphArtifact entity) {
        Map<String, Object> result = summary(entity);
        result.put("graph", entity.getGraphJson());
        result.put("nodes", entity.getNodes());
        result.put("edges", entity.getEdges());
        result.put("statistics", entity.getStatistics());
        result.put("analysis", mapOf(entity.getGraphJson().get("analysis")));
        result.put("latestTasks", entity.getLatestTasks());
        result.put("graphMetadata", entity.getGraphMetadata());
        return result;
    }

    private Map<String, Object> summary(GraphArtifact entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("projectId", entity.getProjectId());
        result.put("bookId", entity.getBookId());
        result.put("graphId", entity.getGraphId());
        result.put("status", entity.getStatus());
        result.put("nodeCount", entity.getNodeCount());
        result.put("edgeCount", entity.getEdgeCount());
        result.put("characterCount", entity.getCharacterCount());
        result.put("locationCount", entity.getLocationCount());
        result.put("organizationCount", entity.getOrganizationCount());
        result.put("itemCount", entity.getItemCount());
        result.put("graphPath", entity.getGraphPath());
        result.put("syncedAt", entity.getSyncedAt());
        result.put("createdAt", entity.getCreatedAt());
        result.put("updatedAt", entity.getUpdatedAt());
        return result;
    }

    private Map<String, Object> statistics(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return statistics(nodes, edges, Map.of());
    }

    private Map<String, Object> statistics(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            Map<String, Object> sourceStatistics) {
        Map<String, Long> nodeTypes = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            nodeTypes.merge(String.valueOf(node.get("node_type")), 1L, Long::sum);
        }
        Map<String, Long> edgeTypes = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            edgeTypes.merge(String.valueOf(edge.get("edge_type")), 1L, Long::sum);
        }

        Map<String, Integer> degree = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            degree.merge(String.valueOf(edge.get("source_id")), 1, Integer::sum);
            degree.merge(String.valueOf(edge.get("target_id")), 1, Integer::sum);
        }
        List<Map<String, Object>> topNodes = nodes.stream()
            .map(node -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", node.get("node_id"));
                item.put("name", node.get("name"));
                item.put("nodeType", node.get("node_type"));
                item.put("degree", degree.getOrDefault(String.valueOf(node.get("node_id")), 0));
                return item;
            })
            .sorted(Comparator.comparing((Map<String, Object> node) -> (Integer) node.get("degree")).reversed())
            .limit(10)
            .toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalNodes", nodes.size());
        stats.put("totalEdges", edges.size());
        stats.put("nodeTypeDistribution", nodeTypes);
        stats.put("edgeTypeDistribution", edgeTypes);
        stats.put("averageDegree", nodes.isEmpty() ? 0.0 : (edges.size() * 2.0) / nodes.size());
        stats.put("density", nodes.size() < 2 ? 0.0 : edges.size() / (double) (nodes.size() * (nodes.size() - 1)));
        stats.put("topNodesByDegree", topNodes);
        if (!sourceStatistics.isEmpty()) {
            stats.put("sourceStatistics", sourceStatistics);
            stats.put("topNodesByCentrality", sourceStatistics.get("top_nodes_by_centrality"));
            stats.put("topNodesByBetweenness", sourceStatistics.get("top_nodes_by_betweenness"));
            stats.put("connectedComponents", sourceStatistics.get("connected_components"));
            stats.put("largestComponentSize", sourceStatistics.get("largest_component_size"));
        }
        return stats;
    }

    private Map<String, Object> metadata(
            String projectId,
            String bookId,
            Path graphFile,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("bookId", bookId);
        metadata.put("graphPath", relative(projectId, graphFile));
        metadata.put("graphModifiedAt", modifiedAt(graphFile));
        metadata.put("nodeCount", nodes.size());
        metadata.put("edgeCount", edges.size());
        metadata.put("latestTasksCount", latestGraphTasks(projectId).size());
        return metadata;
    }

    private List<Map<String, Object>> latestGraphTasks(String projectId) {
        return taskRepository.findByProjectId(projectId).stream()
            .filter(task -> "graph_build".equals(task.getTaskType()))
            .sorted(Comparator.comparing(task -> task.getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(10)
            .map(task -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", task.getId());
                item.put("taskType", task.getTaskType());
                item.put("agentName", task.getAgentName());
                item.put("status", task.getStatus().name());
                item.put("inputRefs", task.getInputRefs());
                item.put("outputRefs", task.getOutputRefs());
                item.put("errors", task.getErrors());
                item.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
                item.put("finishedAt", task.getFinishedAt() == null ? null : task.getFinishedAt().toString());
                return item;
            })
            .toList();
    }

    private Path resolveGraphFile(String projectId, String bookId) {
        List<Path> candidates = List.of(
            projectRoot(projectId).resolve("graph").resolve(bookId + "_graph.json"),
            projectRoot(projectId).resolve("graph").resolve("graph.json"),
            projectRoot(projectId).resolve("books").resolve(bookId).resolve("graph").resolve("knowledge_graph.json")
        );
        return candidates.stream()
            .filter(Files::exists)
            .findFirst()
            .orElse(null);
    }

    private int countByType(List<Map<String, Object>> nodes, String type) {
        return (int) nodes.stream()
            .filter(node -> type.equals(String.valueOf(node.get("node_type"))))
            .count();
    }

    private Map<String, Object> readJsonMap(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取图谱JSON失败: " + file.getFileName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
        }
        return new ArrayList<>();
    }

    private Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private Map<String, Object> asObjectMap(Object value) {
        return mapOf(value);
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private String resolveBookId(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return "default";
        }
        Object bookId = request.containsKey("book_id") ? request.get("book_id") : request.get("bookId");
        return bookId == null || String.valueOf(bookId).isBlank() ? "default" : String.valueOf(bookId);
    }

    private String resolveBookIdValue(String bookId) {
        return bookId == null || bookId.isBlank() ? "default" : bookId;
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private String relative(String projectId, Path path) {
        if (path == null) {
            return null;
        }
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private String modifiedAt(Path file) {
        try {
            return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(file).toInstant(),
                ZoneId.systemDefault()
            ).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
