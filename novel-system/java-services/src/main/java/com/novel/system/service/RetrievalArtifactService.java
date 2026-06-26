package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.Task;
import com.novel.system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RetrievalArtifactService {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final List<String> INDEX_TYPES = List.of("bm25", "vector", "hybrid");

    private final ProjectService projectService;
    private final TaskExecutorService taskExecutorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    public Map<String, Object> getOverview(String projectId) {
        projectService.getProject(projectId);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("projectId", projectId);
        overview.put("config", getConfig(projectId));
        overview.put("indexes", getIndexSummaries(projectId));
        overview.put("indexSummary", readOptionalJson(indexDir(projectId, "bm25").resolve("index_summary.json")));
        overview.put("contextPacks", listContextPacks(projectId));
        overview.put("latestTasks", latestRetrievalTasks(projectId));
        return overview;
    }

    public Map<String, Object> getIndexSummaries(String projectId) {
        projectService.getProject(projectId);
        Map<String, Object> indexes = new LinkedHashMap<>();
        for (String indexType : INDEX_TYPES) {
            indexes.put(indexType, getIndexSummary(projectId, indexType));
        }
        indexes.put("rebuildReport", getRebuildReport(projectId));
        return indexes;
    }

    public Map<String, Object> getIndexSummary(String projectId, String indexType) {
        projectService.getProject(projectId);
        validateIndexType(indexType);
        Path file = indexDir(projectId, indexType).resolve("index_summary.json");
        Map<String, Object> summary = readOptionalJson(file);
        summary.put("indexType", indexType);
        summary.put("path", relative(projectId, file));
        summary.put("exists", Files.exists(file));
        summary.put("updatedAt", Files.exists(file) ? modifiedAt(file) : "");
        return summary;
    }

    public Map<String, Object> getRebuildReport(String projectId) {
        projectService.getProject(projectId);
        Path file = projectRoot(projectId).resolve("indexes").resolve("retrieval_index_report.json");
        Map<String, Object> report = readOptionalJson(file);
        report.put("path", relative(projectId, file));
        report.put("exists", Files.exists(file));
        report.put("updatedAt", Files.exists(file) ? modifiedAt(file) : "");
        return report;
    }

    public Map<String, Object> getConfig(String projectId) {
        projectService.getProject(projectId);
        Path file = configFile(projectId);
        Map<String, Object> config = defaultConfig();
        config.putAll(readOptionalJson(file));
        config.put("path", relative(projectId, file));
        config.put("exists", Files.exists(file));
        config.put("updatedAt", Files.exists(file) ? modifiedAt(file) : "");
        return config;
    }

    public Map<String, Object> updateConfig(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> config = defaultConfig();
        config.putAll(readOptionalJson(configFile(projectId)));

        if (request != null) {
            for (String key : List.of(
                    "use_vector",
                    "use_keyword",
                    "use_graph",
                    "use_rerank",
                    "graph_relation",
                    "graph_hops",
                    "vector_filters",
                    "keyword_filters",
                    "top_k")) {
                if (request.containsKey(key)) {
                    config.put(key, request.get(key));
                }
            }
        }

        writeJson(configFile(projectId), config);
        return getConfig(projectId);
    }

    public Task rebuildIndexes(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.put("project_id", projectId);

        Task task = taskExecutorService.createTask(
            projectId,
            "retrieval_index",
            "retrieval_index",
            Map.of(),
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    public List<Map<String, Object>> listContextPacks(String projectId) {
        projectService.getProject(projectId);
        Path contextDir = indexDir(projectId, "bm25").resolve("context_packs");
        if (!Files.exists(contextDir)) {
            return List.of();
        }

        List<Map<String, Object>> packs = new ArrayList<>();
        try (var stream = Files.list(contextDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    Map<String, Object> data = readOptionalJson(path);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    item.put("path", relative(projectId, path));
                    item.put("updatedAt", modifiedAt(path));
                    item.put("bookId", data.get("book_id"));
                    item.put("volumeNumber", data.get("volume_number"));
                    item.put("chapterNumber", data.get("chapter_number"));
                    item.put("sources", data.get("sources"));
                    item.put("retrievalPlan", data.get("retrieval_plan"));
                    packs.add(item);
                });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read retrieval context packs", e);
        }

        packs.sort(Comparator.comparing(pack -> String.valueOf(pack.get("updatedAt")), Comparator.reverseOrder()));
        return packs;
    }

    public Map<String, Object> getContextPack(String projectId, String contextPackId) {
        projectService.getProject(projectId);
        validateId(contextPackId, "contextPackId");
        Path file = indexDir(projectId, "bm25").resolve("context_packs").resolve(contextPackId + ".json");
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("Context pack does not exist: " + contextPackId);
        }
        Map<String, Object> response = readOptionalJson(file);
        response.put("id", contextPackId);
        response.put("path", relative(projectId, file));
        response.put("updatedAt", modifiedAt(file));
        return response;
    }

    private Path indexDir(String projectId, String indexType) {
        return projectRoot(projectId).resolve("indexes").resolve(indexType);
    }

    private Path configFile(String projectId) {
        return projectRoot(projectId).resolve("indexes").resolve("retrieval_config.json");
    }

    private Map<String, Object> readOptionalJson(Path path) {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("Failed to read retrieval artifact: " + path.getFileName(), e);
        }
    }

    private void writeJson(Path path, Map<String, Object> value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write retrieval config: " + path.getFileName(), e);
        }
    }

    private Map<String, Object> defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("use_vector", true);
        config.put("use_keyword", true);
        config.put("use_graph", true);
        config.put("use_rerank", true);
        config.put("graph_relation", null);
        config.put("graph_hops", 2);
        config.put("vector_filters", new LinkedHashMap<>());
        config.put("keyword_filters", new LinkedHashMap<>());
        config.put("top_k", 12);
        return config;
    }

    private List<Map<String, Object>> latestRetrievalTasks(String projectId) {
        return taskExecutorService.listTasksByProject(projectId).stream()
            .filter(task -> "retrieval_index".equals(task.getTaskType()))
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

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private String modifiedAt(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private void validateId(String value, String fieldName) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value);
        }
    }

    private void validateIndexType(String indexType) {
        if (!INDEX_TYPES.contains(indexType)) {
            throw new IllegalArgumentException("Unsupported retrieval index type: " + indexType);
        }
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }
}
