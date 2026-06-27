package com.novel.system.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.Task;
import com.novel.system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GraphArtifactService {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private final ProjectService projectService;
    private final TaskExecutorService taskExecutorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    public Map<String, Object> getGraph(String projectId, String bookId) {
        projectService.getProject(projectId);
        Path graphFile = resolveGraphFile(projectId, resolveBookId(projectId, bookId));
        Map<String, Object> graph = readJsonMap(graphFile);
        graph.put("path", relative(projectId, graphFile));
        graph.put("updatedAt", modifiedAt(graphFile));
        graph.put("statistics", statistics(graph));
        graph.putIfAbsent("analysis", analysis(graph));
        graph.put("latestTasks", latestGraphTasks(projectId));
        return graph;
    }

    public Map<String, Object> queryGraph(String projectId, String bookId, Map<String, Object> request) {
        String resolvedBookId = resolveBookId(projectId, bookId);
        Map<String, Object> cache = readQueryCache(projectId, resolvedBookId, request);
        if (cache != null) {
            return cache;
        }

        Map<String, Object> graph = getGraph(projectId, resolvedBookId);
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(graph.get("edges"));

        String queryType = stringValue(request, "queryType", stringValue(request, "query_type", "subgraph"));
        String nodeType = stringValue(request, "nodeType", stringValue(request, "node_type", null));
        String nodeName = stringValue(request, "nodeName", stringValue(request, "node_name", null));
        String edgeType = stringValue(request, "edgeType", stringValue(request, "edge_type", null));
        String centerNode = stringValue(request, "centerNode", stringValue(request, "center_node", null));
        String sourceNode = stringValue(request, "sourceNode", stringValue(request, "source_node", null));
        String targetNode = stringValue(request, "targetNode", stringValue(request, "target_node", null));
        int limit = intValue(request.getOrDefault("limit", 20), 20);
        int radius = intValue(request.getOrDefault("radius", request.getOrDefault("maxDepth", 2)), 2);

        if ("analysis".equals(queryType)) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("queryType", queryType);
            response.put("statistics", graph.get("statistics"));
            response.put("analysis", graph.get("analysis"));
            response.put("totalFound", 1);
            response.put("returned", 1);
            return writeQueryCache(projectId, resolvedBookId, request, response);
        }

        List<Map<String, Object>> matchedNodes = nodes.stream()
            .filter(node -> nodeType == null || nodeType.equals(String.valueOf(node.get("node_type"))))
            .filter(node -> nodeName == null || containsIgnoreCase(node.get("name"), nodeName)
                || containsIgnoreCase(node.get("node_id"), nodeName))
            .limit(limit)
            .collect(Collectors.toCollection(ArrayList::new));

        List<Map<String, Object>> matchedEdges = edges.stream()
            .filter(edge -> edgeType == null || edgeType.equals(String.valueOf(edge.get("edge_type"))))
            .limit(limit)
            .collect(Collectors.toCollection(ArrayList::new));

        List<List<String>> paths = new ArrayList<>();
        if ("path".equals(queryType) && sourceNode != null && targetNode != null) {
            Optional<List<String>> path = shortestPath(nodes, edges, sourceNode, targetNode, radius);
            path.ifPresent(paths::add);
            Set<String> pathNodeIds = path.map(HashSet::new).orElseGet(HashSet::new);
            matchedNodes = nodes.stream()
                .filter(node -> pathNodeIds.contains(String.valueOf(node.get("node_id"))))
                .collect(Collectors.toCollection(ArrayList::new));
            matchedEdges = edges.stream()
                .filter(edge -> pathNodeIds.contains(String.valueOf(edge.get("source_id")))
                    && pathNodeIds.contains(String.valueOf(edge.get("target_id"))))
                .collect(Collectors.toCollection(ArrayList::new));
        } else if ("subgraph".equals(queryType) || centerNode != null) {
            String center = centerNode != null ? centerNode : nodeName;
            if (center != null) {
                Set<String> subgraphNodeIds = neighbors(nodes, edges, center, radius);
                matchedNodes = nodes.stream()
                    .filter(node -> subgraphNodeIds.contains(String.valueOf(node.get("node_id"))))
                    .limit(limit)
                    .collect(Collectors.toCollection(ArrayList::new));
                matchedEdges = edges.stream()
                    .filter(edge -> subgraphNodeIds.contains(String.valueOf(edge.get("source_id")))
                        && subgraphNodeIds.contains(String.valueOf(edge.get("target_id"))))
                    .limit(limit)
                    .collect(Collectors.toCollection(ArrayList::new));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("queryType", queryType);
        response.put("nodes", matchedNodes);
        response.put("edges", matchedEdges);
        response.put("paths", paths);
        response.put("totalFound", matchedNodes.size() + matchedEdges.size() + paths.size());
        response.put("returned", matchedNodes.size() + matchedEdges.size());
        return writeQueryCache(projectId, resolvedBookId, request, response);
    }

    public Task rebuildGraph(String projectId, String bookId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Map<String, Object> version = createGraphVersionIfPresent(
            projectId,
            resolvedBookId,
            "before_graph_rebuild",
            stringValue(parameters, "actor", "system"),
            stringValue(parameters, "note", "Snapshot before graph rebuild")
        );
        parameters.put("project_id", projectId);
        parameters.put("book_id", resolvedBookId);
        if (!version.isEmpty()) {
            parameters.put("previous_graph_version_id", version.get("id"));
            parameters.put("previous_graph_version_path", version.get("path"));
        }

        Task task = taskExecutorService.createTask(
            projectId,
            "graph_build",
            "graph_build",
            Map.of(),
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    public Map<String, Object> createGraphVersion(String projectId, String bookId, Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Map<String, Object> options = request == null ? Map.of() : request;
        return createGraphVersion(
            projectId,
            resolvedBookId,
            stringValue(options, "reason", "manual_graph_snapshot"),
            stringValue(options, "actor", stringValue(options, "user", "human")),
            stringValue(options, "note", "")
        );
    }

    public List<Map<String, Object>> listGraphVersions(String projectId, String bookId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path versionsDir = graphVersionsDir(projectId, resolvedBookId);
        if (!Files.exists(versionsDir)) {
            return List.of();
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        try (var stream = Files.list(versionsDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        versions.add(graphVersionSummary(projectId, path, readJsonMap(path)));
                    } catch (Exception ignored) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                        item.put("bookId", resolvedBookId);
                        item.put("path", relative(projectId, path));
                        item.put("createdAt", modifiedAt(path));
                        versions.add(item);
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("读取图谱版本目录失败", e);
        }
        versions.sort(Comparator.comparing(version -> String.valueOf(version.get("createdAt")), Comparator.reverseOrder()));
        return versions;
    }

    public Map<String, Object> getGraphVersion(String projectId, String bookId, String versionId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        validateId(versionId, "versionId");
        Path versionFile = graphVersionsDir(projectId, resolvedBookId).resolve(versionId + ".json");
        if (!Files.exists(versionFile)) {
            throw new ResourceNotFoundException("图谱版本不存在: " + versionId);
        }
        Map<String, Object> version = readJsonMap(versionFile);
        version.put("id", versionId);
        version.put("path", relative(projectId, versionFile));
        version.put("updatedAt", modifiedAt(versionFile));
        return version;
    }

    public Map<String, Object> listQueryCaches(String projectId, String bookId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path cacheDir = queryCacheDir(projectId);
        List<Map<String, Object>> items = new ArrayList<>();
        if (Files.exists(cacheDir)) {
            try (var stream = Files.list(cacheDir)) {
                stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            Map<String, Object> cache = readJsonMap(path);
                            if (resolvedBookId.equals(cache.get("bookId")) || resolvedBookId.equals(cache.get("book_id"))) {
                                items.add(cacheSummary(projectId, path, cache));
                            }
                        } catch (Exception ignored) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                            item.put("path", relative(projectId, path));
                            item.put("updatedAt", modifiedAt(path));
                            items.add(item);
                        }
                    });
            } catch (IOException e) {
                throw new RuntimeException("读取图谱查询缓存失败", e);
            }
        }
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("createdAt")), Comparator.reverseOrder()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("bookId", resolvedBookId);
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> clearQueryCaches(String projectId, String bookId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path cacheDir = queryCacheDir(projectId);
        int deleted = 0;
        if (Files.exists(cacheDir)) {
            try (var stream = Files.list(cacheDir)) {
                for (Path path : stream
                    .filter(candidate -> Files.isRegularFile(candidate) && candidate.getFileName().toString().endsWith(".json"))
                    .toList()) {
                    Map<String, Object> cache = readJsonMap(path);
                    if (resolvedBookId.equals(cache.get("bookId")) || resolvedBookId.equals(cache.get("book_id"))) {
                        Files.deleteIfExists(path);
                        deleted++;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("清理图谱查询缓存失败", e);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("bookId", resolvedBookId);
        response.put("deletedCount", deleted);
        response.put("status", "cleared");
        return response;
    }

    public ExportedGraph exportGraph(String projectId, String bookId, String format) {
        Map<String, Object> graph = getGraph(projectId, bookId);
        String normalizedFormat = format == null || format.isBlank() ? "json" : format.toLowerCase();
        return switch (normalizedFormat) {
            case "json" -> new ExportedGraph(
                "knowledge_graph.json",
                MediaType.APPLICATION_JSON,
                toJsonBytes(graph)
            );
            case "graphml" -> new ExportedGraph(
                "knowledge_graph.graphml",
                MediaType.APPLICATION_XML,
                graphml(graph).getBytes(StandardCharsets.UTF_8)
            );
            case "cytoscape" -> new ExportedGraph(
                "knowledge_graph_cytoscape.json",
                MediaType.APPLICATION_JSON,
                toJsonBytes(cytoscape(graph))
            );
            case "html" -> new ExportedGraph(
                "knowledge_graph.html",
                MediaType.TEXT_HTML,
                html(graph).getBytes(StandardCharsets.UTF_8)
            );
            default -> throw new IllegalArgumentException("不支持的图谱导出格式: " + format);
        };
    }

    public String resolveBookId(String projectId, String requestedBookId) {
        if (requestedBookId != null && !requestedBookId.isBlank() && !"default".equals(requestedBookId)) {
            validateId(requestedBookId, "bookId");
            return requestedBookId;
        }

        return latestOutlineFile(projectId)
            .map(path -> {
                Map<String, Object> outline = readJsonMap(path);
                Object bookId = outline.get("book_id");
                return bookId == null ? stripSuffix(path.getFileName().toString(), "_outline.json") : String.valueOf(bookId);
            })
            .orElse("default");
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
            .orElseThrow(() -> new ResourceNotFoundException("图谱不存在，请先构建图谱"));
    }

    private Map<String, Object> statistics(Map<String, Object> graph) {
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(graph.get("edges"));
        Map<String, Object> sourceStatistics = mapOf(graph.get("statistics"));
        Map<String, Long> nodeTypes = nodes.stream()
            .collect(Collectors.groupingBy(node -> String.valueOf(node.get("node_type")), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> edgeTypes = edges.stream()
            .collect(Collectors.groupingBy(edge -> String.valueOf(edge.get("edge_type")), LinkedHashMap::new, Collectors.counting()));

        Map<String, Integer> degree = new HashMap<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            adjacency.put(String.valueOf(node.get("node_id")), new HashSet<>());
        }
        for (Map<String, Object> edge : edges) {
            String source = String.valueOf(edge.get("source_id"));
            String target = String.valueOf(edge.get("target_id"));
            degree.merge(source, 1, Integer::sum);
            degree.merge(target, 1, Integer::sum);
            adjacency.computeIfAbsent(source, ignored -> new HashSet<>()).add(target);
            adjacency.computeIfAbsent(target, ignored -> new HashSet<>()).add(source);
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
            .sorted(Comparator.comparing((Map<String, Object> node) -> intValue(node.get("degree"), 0)).reversed())
            .limit(10)
            .toList();
        List<List<String>> components = connectedComponents(adjacency);
        List<Map<String, Object>> centralityNodes = topCentralityNodes(nodes, degree, Math.max(nodes.size() - 1, 1));
        List<Map<String, Object>> isolatedNodes = nodes.stream()
            .filter(node -> degree.getOrDefault(String.valueOf(node.get("node_id")), 0) == 0)
            .map(node -> nodeSummary(node, Map.of("degree", 0)))
            .limit(20)
            .toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalNodes", nodes.size());
        stats.put("totalEdges", edges.size());
        stats.put("nodeTypeDistribution", nodeTypes);
        stats.put("edgeTypeDistribution", edgeTypes);
        stats.put("averageDegree", nodes.isEmpty() ? 0.0 : (edges.size() * 2.0) / nodes.size());
        stats.put("density", nodes.size() < 2 ? 0.0 : edges.size() / (double) (nodes.size() * (nodes.size() - 1)));
        stats.put("connectedComponents", components.size());
        stats.put("largestComponentSize", components.stream().mapToInt(List::size).max().orElse(0));
        stats.put("topNodesByDegree", topNodes);
        stats.put("topNodesByCentrality", sourceStatistics.getOrDefault("top_nodes_by_centrality", centralityNodes));
        stats.put("topNodesByBetweenness", sourceStatistics.getOrDefault("top_nodes_by_betweenness", List.of()));
        stats.put("isolatedNodes", isolatedNodes);
        if (!sourceStatistics.isEmpty()) {
            stats.put("sourceStatistics", sourceStatistics);
            stats.putIfAbsent("connectedComponents", sourceStatistics.get("connected_components"));
            stats.putIfAbsent("largestComponentSize", sourceStatistics.get("largest_component_size"));
        }
        return stats;
    }

    private Map<String, Object> analysis(Map<String, Object> graph) {
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(graph.get("edges"));
        Map<String, Object> sourceAnalysis = mapOf(graph.get("analysis"));
        if (!sourceAnalysis.isEmpty()) {
            return sourceAnalysis;
        }

        Map<String, Integer> degree = new HashMap<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            adjacency.put(String.valueOf(node.get("node_id")), new HashSet<>());
        }
        for (Map<String, Object> edge : edges) {
            String source = String.valueOf(edge.get("source_id"));
            String target = String.valueOf(edge.get("target_id"));
            degree.merge(source, 1, Integer::sum);
            degree.merge(target, 1, Integer::sum);
            adjacency.computeIfAbsent(source, ignored -> new HashSet<>()).add(target);
            adjacency.computeIfAbsent(target, ignored -> new HashSet<>()).add(source);
        }

        List<List<String>> components = connectedComponents(adjacency);
        Map<String, Map<String, Object>> nodeById = nodes.stream()
            .collect(Collectors.toMap(
                node -> String.valueOf(node.get("node_id")),
                node -> node,
                (left, ignored) -> left,
                LinkedHashMap::new
            ));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("componentSummary", components.stream()
            .limit(10)
            .map(component -> Map.of(
                "size", component.size(),
                "nodes", component.stream()
                    .limit(12)
                    .map(nodeById::get)
                    .filter(java.util.Objects::nonNull)
                    .map(node -> nodeSummary(node, Map.of()))
                    .toList()
            ))
            .toList());
        result.put("isolatedNodes", nodes.stream()
            .filter(node -> degree.getOrDefault(String.valueOf(node.get("node_id")), 0) == 0)
            .map(node -> nodeSummary(node, Map.of("degree", 0)))
            .limit(20)
            .toList());
        result.put("warnings", graphWarnings(nodes, edges, components));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readQueryCache(String projectId, String bookId, Map<String, Object> request) {
        Path graphFile = resolveGraphFile(projectId, bookId);
        String cacheId = queryCacheId(projectId, bookId, graphFile, request);
        Path cacheFile = queryCacheDir(projectId).resolve(cacheId + ".json");
        if (!Files.exists(cacheFile)) {
            return null;
        }
        try {
            Map<String, Object> cached = readJsonMap(cacheFile);
            if (!String.valueOf(modifiedAt(graphFile)).equals(String.valueOf(cached.get("graphUpdatedAt")))) {
                return null;
            }
            Object rawResponse = cached.get("response");
            if (!(rawResponse instanceof Map<?, ?> responseMap)) {
                return null;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            responseMap.forEach((key, value) -> response.put(String.valueOf(key), value));
            response.put("cacheHit", true);
            response.put("cacheId", cacheId);
            response.put("cachePath", relative(projectId, cacheFile));
            response.put("cachedAt", cached.get("createdAt"));
            return response;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> writeQueryCache(
            String projectId,
            String bookId,
            Map<String, Object> request,
            Map<String, Object> response) {
        Path graphFile = resolveGraphFile(projectId, bookId);
        String cacheId = queryCacheId(projectId, bookId, graphFile, request);
        Path cacheDir = queryCacheDir(projectId);
        Path cacheFile = cacheDir.resolve(cacheId + ".json");
        try {
            Files.createDirectories(cacheDir);
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("id", cacheId);
            cache.put("projectId", projectId);
            cache.put("bookId", bookId);
            cache.put("book_id", bookId);
            cache.put("request", normalizedQueryRequest(request));
            cache.put("graphPath", relative(projectId, graphFile));
            cache.put("graphUpdatedAt", modifiedAt(graphFile));
            cache.put("createdAt", LocalDateTime.now().toString());
            cache.put("response", response);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), cache);
        } catch (IOException e) {
            throw new RuntimeException("写入图谱查询缓存失败", e);
        }

        Map<String, Object> cachedResponse = new LinkedHashMap<>(response);
        cachedResponse.put("cacheHit", false);
        cachedResponse.put("cacheId", cacheId);
        cachedResponse.put("cachePath", relative(projectId, cacheFile));
        return cachedResponse;
    }

    private Map<String, Object> cacheSummary(String projectId, Path cacheFile, Map<String, Object> cache) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", cache.getOrDefault("id", stripSuffix(cacheFile.getFileName().toString(), ".json")));
        item.put("bookId", cache.get("bookId"));
        item.put("queryType", mapOf(cache.get("request")).get("queryType"));
        item.put("request", cache.get("request"));
        item.put("graphPath", cache.get("graphPath"));
        item.put("graphUpdatedAt", cache.get("graphUpdatedAt"));
        item.put("createdAt", cache.get("createdAt"));
        item.put("path", relative(projectId, cacheFile));
        item.put("updatedAt", modifiedAt(cacheFile));
        Map<String, Object> response = mapOf(cache.get("response"));
        item.put("totalFound", response.get("totalFound"));
        item.put("returned", response.get("returned"));
        return item;
    }

    private Map<String, Object> createGraphVersionIfPresent(
            String projectId,
            String bookId,
            String reason,
            String actor,
            String note) {
        try {
            return createGraphVersion(projectId, bookId, reason, actor, note);
        } catch (ResourceNotFoundException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> createGraphVersion(
            String projectId,
            String bookId,
            String reason,
            String actor,
            String note) {
        Path graphFile = resolveGraphFile(projectId, bookId);
        Map<String, Object> graph = readJsonMap(graphFile);
        Map<String, Object> statistics = statistics(graph);
        Map<String, Object> analysis = analysis(graph);
        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String versionId = "graph_" + bookId + "_" + timestamp;
        Path versionFile = graphVersionsDir(projectId, bookId).resolve(versionId + ".json");

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", versionId);
        version.put("projectId", projectId);
        version.put("bookId", bookId);
        version.put("reason", reason);
        version.put("actor", actor);
        version.put("note", note);
        version.put("createdAt", LocalDateTime.now().toString());
        version.put("sourcePath", relative(projectId, graphFile));
        version.put("sourceModifiedAt", modifiedAt(graphFile));
        version.put("graphId", graph.get("graph_id"));
        version.put("nodeCount", listOfMaps(graph.get("nodes")).size());
        version.put("edgeCount", listOfMaps(graph.get("edges")).size());
        version.put("statistics", statistics);
        version.put("analysis", analysis);
        version.put("graph", graph);

        try {
            Files.createDirectories(versionFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(versionFile.toFile(), version);
        } catch (IOException e) {
            throw new RuntimeException("写入图谱版本失败", e);
        }

        Map<String, Object> response = graphVersionSummary(projectId, versionFile, version);
        response.put("graph", graph);
        response.put("statistics", statistics);
        response.put("analysis", analysis);
        return response;
    }

    private Map<String, Object> graphVersionSummary(String projectId, Path versionFile, Map<String, Object> version) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", version.getOrDefault("id", stripSuffix(versionFile.getFileName().toString(), ".json")));
        item.put("projectId", version.get("projectId"));
        item.put("bookId", version.get("bookId"));
        item.put("reason", version.get("reason"));
        item.put("actor", version.get("actor"));
        item.put("note", version.get("note"));
        item.put("createdAt", version.getOrDefault("createdAt", modifiedAt(versionFile)));
        item.put("sourcePath", version.get("sourcePath"));
        item.put("sourceModifiedAt", version.get("sourceModifiedAt"));
        item.put("graphId", version.get("graphId"));
        item.put("nodeCount", version.get("nodeCount"));
        item.put("edgeCount", version.get("edgeCount"));
        item.put("path", relative(projectId, versionFile));
        item.put("updatedAt", modifiedAt(versionFile));
        return item;
    }

    private String queryCacheId(String projectId, String bookId, Path graphFile, Map<String, Object> request) {
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("projectId", projectId);
        signature.put("bookId", bookId);
        signature.put("graphPath", relative(projectId, graphFile));
        signature.put("graphUpdatedAt", modifiedAt(graphFile));
        signature.put("request", normalizedQueryRequest(request));
        return "query_" + sha1(signature).substring(0, 16);
    }

    private Map<String, Object> normalizedQueryRequest(Map<String, Object> request) {
        Map<String, Object> normalized = new java.util.TreeMap<>();
        if (request != null) {
            request.forEach((key, value) -> {
                if (key != null && value != null) {
                    normalized.put(key, value);
                }
            });
        }
        return new LinkedHashMap<>(normalized);
    }

    private String sha1(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(jsonString(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private List<Map<String, Object>> latestGraphTasks(String projectId) {
        return taskExecutorService.listTasksByProject(projectId).stream()
            .filter(task -> "graph_build".equals(task.getTaskType()))
            .sorted(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(10)
            .map(task -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", task.getId());
                item.put("status", task.getStatus().name());
                item.put("result", task.getResult());
                item.put("errors", task.getErrors());
                item.put("createdAt", task.getCreatedAt());
                item.put("finishedAt", task.getFinishedAt());
                return item;
            })
            .toList();
    }

    private Set<String> neighbors(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String center, int radius) {
        String centerId = findNodeId(nodes, center).orElse(center);
        Map<String, Set<String>> adjacency = adjacency(edges);
        Set<String> visited = new HashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        visited.add(centerId);
        queue.add(new NodeDepth(centerId, 0));

        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth >= radius) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current.nodeId, Set.of())) {
                if (visited.add(next)) {
                    queue.add(new NodeDepth(next, current.depth + 1));
                }
            }
        }
        return visited;
    }

    private Optional<List<String>> shortestPath(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String source,
            String target,
            int maxDepth) {
        String sourceId = findNodeId(nodes, source).orElse(source);
        String targetId = findNodeId(nodes, target).orElse(target);
        Map<String, Set<String>> adjacency = adjacency(edges);
        ArrayDeque<List<String>> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(List.of(sourceId));
        visited.add(sourceId);

        while (!queue.isEmpty()) {
            List<String> path = queue.removeFirst();
            String current = path.get(path.size() - 1);
            if (current.equals(targetId)) {
                return Optional.of(path);
            }
            if (path.size() > maxDepth + 1) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    List<String> nextPath = new ArrayList<>(path);
                    nextPath.add(next);
                    queue.add(nextPath);
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Set<String>> adjacency(List<Map<String, Object>> edges) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            String source = String.valueOf(edge.get("source_id"));
            String target = String.valueOf(edge.get("target_id"));
            adjacency.computeIfAbsent(source, ignored -> new HashSet<>()).add(target);
            adjacency.computeIfAbsent(target, ignored -> new HashSet<>()).add(source);
        }
        return adjacency;
    }

    private List<List<String>> connectedComponents(Map<String, Set<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        List<List<String>> components = new ArrayList<>();
        for (String nodeId : adjacency.keySet()) {
            if (visited.contains(nodeId)) {
                continue;
            }
            List<String> component = new ArrayList<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(nodeId);
            visited.add(nodeId);
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                component.add(current);
                for (String next : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            components.add(component);
        }
        components.sort(Comparator.comparing(List<String>::size).reversed());
        return components;
    }

    private List<Map<String, Object>> topCentralityNodes(
            List<Map<String, Object>> nodes,
            Map<String, Integer> degree,
            int denominator) {
        return nodes.stream()
            .map(node -> {
                String nodeId = String.valueOf(node.get("node_id"));
                double centrality = degree.getOrDefault(nodeId, 0) / (double) denominator;
                return nodeSummary(node, Map.of("degreeCentrality", round(centrality), "degree", degree.getOrDefault(nodeId, 0)));
            })
            .sorted(Comparator.comparing((Map<String, Object> node) -> doubleValue(node.get("degreeCentrality"))).reversed())
            .limit(10)
            .toList();
    }

    private List<Map<String, Object>> graphWarnings(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            List<List<String>> components) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (!nodes.isEmpty() && edges.isEmpty()) {
            warnings.add(Map.of(
                "code", "GRAPH_HAS_NO_EDGES",
                "severity", "warning",
                "message", "图谱已有节点但缺少关系边，建议补充人物关系、剧情涉及角色或设定关联。"
            ));
        }
        if (components.size() > 1) {
            warnings.add(Map.of(
                "code", "GRAPH_DISCONNECTED",
                "severity", "info",
                "message", "图谱包含 " + components.size() + " 个连通分量，可能存在孤立剧情线或未关联设定。"
            ));
        }
        return warnings;
    }

    private Map<String, Object> nodeSummary(Map<String, Object> node, Map<String, Object> extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.get("node_id"));
        result.put("node_id", node.get("node_id"));
        result.put("name", node.get("name"));
        result.put("nodeType", node.get("node_type"));
        result.put("node_type", node.get("node_type"));
        result.putAll(extra);
        return result;
    }

    private Optional<String> findNodeId(List<Map<String, Object>> nodes, String idOrName) {
        return nodes.stream()
            .filter(node -> idOrName.equals(String.valueOf(node.get("node_id")))
                || idOrName.equals(String.valueOf(node.get("name"))))
            .map(node -> String.valueOf(node.get("node_id")))
            .findFirst();
    }

    private Map<String, Object> cytoscape(Map<String, Object> graph) {
        List<Map<String, Object>> elements = new ArrayList<>();
        for (Map<String, Object> node : listOfMaps(graph.get("nodes"))) {
            elements.add(Map.of("data", Map.of(
                "id", node.get("node_id"),
                "label", node.get("name"),
                "type", node.get("node_type")
            )));
        }
        for (Map<String, Object> edge : listOfMaps(graph.get("edges"))) {
            elements.add(Map.of("data", Map.of(
                "id", edge.get("edge_id"),
                "source", edge.get("source_id"),
                "target", edge.get("target_id"),
                "label", edge.get("edge_type")
            )));
        }
        return Map.of("elements", elements);
    }

    private String graphml(Map<String, Object> graph) {
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        builder.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
        builder.append("  <graph edgedefault=\"directed\">\n");
        for (Map<String, Object> node : listOfMaps(graph.get("nodes"))) {
            builder.append("    <node id=\"").append(xml(node.get("node_id"))).append("\">\n");
            builder.append("      <data key=\"name\">").append(xml(node.get("name"))).append("</data>\n");
            builder.append("      <data key=\"type\">").append(xml(node.get("node_type"))).append("</data>\n");
            builder.append("    </node>\n");
        }
        for (Map<String, Object> edge : listOfMaps(graph.get("edges"))) {
            builder.append("    <edge id=\"").append(xml(edge.get("edge_id"))).append("\" source=\"")
                .append(xml(edge.get("source_id"))).append("\" target=\"")
                .append(xml(edge.get("target_id"))).append("\">\n");
            builder.append("      <data key=\"type\">").append(xml(edge.get("edge_type"))).append("</data>\n");
            builder.append("    </edge>\n");
        }
        builder.append("  </graph>\n</graphml>\n");
        return builder.toString();
    }

    private String html(Map<String, Object> graph) {
        return """
            <!doctype html>
            <html lang=\"zh-CN\">
            <head><meta charset=\"utf-8\"><title>Knowledge Graph</title></head>
            <body>
            <h1>Knowledge Graph</h1>
            <pre id=\"graph\"></pre>
            <script>
            const graph = %s;
            document.getElementById('graph').textContent = JSON.stringify(graph, null, 2);
            </script>
            </body>
            </html>
            """.formatted(jsonString(graph));
    }

    private byte[] toJsonBytes(Object value) {
        return jsonString(value).getBytes(StandardCharsets.UTF_8);
    }

    private String jsonString(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化图谱失败", e);
        }
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
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private Optional<Path> latestOutlineFile(String projectId) {
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(
            projectRoot(projectId).resolve("novel").resolve("outline"),
            projectRoot(projectId).resolve("outlines")
        )) {
            if (!Files.exists(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith("_outline.json"))
                    .forEach(files::add);
            } catch (IOException e) {
                throw new RuntimeException("读取大纲目录失败", e);
            }
        }
        return files.stream().max(Comparator.comparing(this::modifiedAt));
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private Path queryCacheDir(String projectId) {
        return projectRoot(projectId).resolve("graph").resolve("query_cache");
    }

    private Path graphVersionsDir(String projectId, String bookId) {
        return projectRoot(projectId).resolve("graph").resolve("versions").resolve(bookId);
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private LocalDateTime modifiedTime(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.MIN;
        }
    }

    private String modifiedAt(Path file) {
        LocalDateTime time = modifiedTime(file);
        return LocalDateTime.MIN.equals(time) ? "" : time.toString();
    }

    private boolean containsIgnoreCase(Object value, String query) {
        return value != null && String.valueOf(value).toLowerCase().contains(query.toLowerCase());
    }

    private String stringValue(Map<String, Object> request, String key, String fallback) {
        Object value = request == null ? null : request.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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

    private double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private void validateId(String value, String fieldName) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("非法" + fieldName + ": " + value);
        }
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private String xml(Object value) {
        return String.valueOf(value == null ? "" : value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private record NodeDepth(String nodeId, int depth) {}

    public record ExportedGraph(String filename, MediaType mediaType, byte[] bytes) {}
}
