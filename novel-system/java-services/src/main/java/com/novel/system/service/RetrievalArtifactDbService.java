package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.RetrievalArtifact;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.RetrievalArtifactRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalArtifactDbService {

    private static final List<String> INDEX_TYPES = List.of("bm25", "vector", "hybrid");

    private final ProjectService projectService;
    private final RetrievalArtifactRepository retrievalArtifactRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Transactional
    public Map<String, Object> syncRetrievalFromWorkspace(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);

        Map<String, Object> config = readOptionalJson(configFile(projectId));
        Map<String, Object> bm25Summary = readOptionalJson(indexDir(projectId, "bm25").resolve("index_summary.json"));
        Map<String, Object> vectorSummary = readOptionalJson(indexDir(projectId, "vector").resolve("index_summary.json"));
        Map<String, Object> hybridSummary = readOptionalJson(indexDir(projectId, "hybrid").resolve("index_summary.json"));
        Path reportFile = projectRoot(projectId).resolve("indexes").resolve("retrieval_index_report.json");
        Map<String, Object> rebuildReport = readOptionalJson(reportFile);
        List<Map<String, Object>> contextPacks = listContextPacks(projectId);

        boolean anyIndex = !bm25Summary.isEmpty() || !vectorSummary.isEmpty() || !hybridSummary.isEmpty();

        RetrievalArtifact entity = retrievalArtifactRepository.findByProjectId(projectId)
            .orElseGet(RetrievalArtifact::new);
        entity.setId(projectId);
        entity.setProjectId(projectId);
        entity.setStatus(anyIndex || !contextPacks.isEmpty() ? "synced" : "empty");
        entity.setBm25DocumentCount(documentCount(bm25Summary));
        entity.setVectorDocumentCount(documentCount(vectorSummary));
        entity.setHybridDocumentCount(documentCount(hybridSummary));
        entity.setContextPackCount(contextPacks.size());
        entity.setConfigPath(relative(projectId, configFile(projectId)));
        entity.setRebuildReportPath(relative(projectId, reportFile));
        entity.setConfig(config);
        entity.setBm25Summary(bm25Summary);
        entity.setVectorSummary(vectorSummary);
        entity.setHybridSummary(hybridSummary);
        entity.setRebuildReport(rebuildReport);
        entity.setContextPacks(contextPacks);
        entity.setLatestTasks(latestRetrievalTasks(projectId));
        entity.setRetrievalMetadata(metadata(projectId, bm25Summary, vectorSummary, hybridSummary, contextPacks));
        entity.setSyncedAt(LocalDateTime.now());

        RetrievalArtifact saved = retrievalArtifactRepository.save(entity);
        log.info("Synced retrieval artifact to DB: project={}, bm25={}, vector={}, hybrid={}, contextPacks={}",
            projectId,
            saved.getBm25DocumentCount(),
            saved.getVectorDocumentCount(),
            saved.getHybridDocumentCount(),
            saved.getContextPackCount());
        return response(saved);
    }

    public Map<String, Object> getRetrieval(String projectId) {
        projectService.getProject(projectId);
        RetrievalArtifact artifact = retrievalArtifactRepository.findByProjectId(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("数据库检索产物不存在，请先同步: " + projectId));
        return response(artifact);
    }

    public List<Map<String, Object>> listRetrieval(String projectId) {
        projectService.getProject(projectId);
        return retrievalArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(projectId)
            .stream()
            .map(this::summary)
            .toList();
    }

    private Map<String, Object> response(RetrievalArtifact entity) {
        Map<String, Object> result = summary(entity);
        result.put("config", entity.getConfig());
        result.put("bm25Summary", entity.getBm25Summary());
        result.put("vectorSummary", entity.getVectorSummary());
        result.put("hybridSummary", entity.getHybridSummary());
        result.put("rebuildReport", entity.getRebuildReport());
        result.put("contextPacks", entity.getContextPacks());
        result.put("latestTasks", entity.getLatestTasks());
        result.put("retrievalMetadata", entity.getRetrievalMetadata());
        return result;
    }

    private Map<String, Object> summary(RetrievalArtifact entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("projectId", entity.getProjectId());
        result.put("status", entity.getStatus());
        result.put("bm25DocumentCount", entity.getBm25DocumentCount());
        result.put("vectorDocumentCount", entity.getVectorDocumentCount());
        result.put("hybridDocumentCount", entity.getHybridDocumentCount());
        result.put("contextPackCount", entity.getContextPackCount());
        result.put("configPath", entity.getConfigPath());
        result.put("rebuildReportPath", entity.getRebuildReportPath());
        result.put("syncedAt", entity.getSyncedAt());
        result.put("createdAt", entity.getCreatedAt());
        result.put("updatedAt", entity.getUpdatedAt());
        return result;
    }

    private Map<String, Object> metadata(
            String projectId,
            Map<String, Object> bm25Summary,
            Map<String, Object> vectorSummary,
            Map<String, Object> hybridSummary,
            List<Map<String, Object>> contextPacks) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("bm25DocumentCount", documentCount(bm25Summary));
        metadata.put("vectorDocumentCount", documentCount(vectorSummary));
        metadata.put("hybridDocumentCount", documentCount(hybridSummary));
        metadata.put("contextPackCount", contextPacks.size());
        metadata.put("indexTypes", INDEX_TYPES);
        metadata.put("latestQualityEvaluation", hybridSummary.get("quality_evaluation"));
        metadata.put("latestCitationBudget", hybridSummary.get("citation_budget"));
        metadata.put("latestTasksCount", latestRetrievalTasks(projectId).size());
        return metadata;
    }

    private List<Map<String, Object>> latestRetrievalTasks(String projectId) {
        return taskRepository.findByProjectId(projectId).stream()
            .filter(task -> "retrieval_index".equals(task.getTaskType()))
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

    private List<Map<String, Object>> listContextPacks(String projectId) {
        Path contextDir = indexDir(projectId, "bm25").resolve("context_packs");
        if (!Files.exists(contextDir)) {
            return new ArrayList<>();
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
                    item.put("qualityEvaluation", data.get("quality_evaluation"));
                    item.put("citationBudget", data.get("citation_budget"));
                    packs.add(item);
                });
        } catch (IOException e) {
            throw new RuntimeException("读取检索上下文包失败", e);
        }
        packs.sort(Comparator.comparing(pack -> String.valueOf(pack.get("updatedAt")), Comparator.reverseOrder()));
        return packs;
    }

    private int documentCount(Map<String, Object> summary) {
        if (summary == null) {
            return 0;
        }
        for (String key : List.of("document_count", "documentCount", "vector_count", "returned_count", "total_documents")) {
            Object value = summary.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(value.toString());
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return 0;
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
            throw new RuntimeException("读取检索产物失败: " + path.getFileName(), e);
        }
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
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }
}
