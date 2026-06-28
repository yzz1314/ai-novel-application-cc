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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RetrievalArtifactService {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final List<String> INDEX_TYPES = List.of("bm25", "vector", "hybrid");
    private static final DateTimeFormatter VERSION_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

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
        overview.put("qualityReport", getQualityReport(projectId));
        overview.put("benchmarkReport", getBenchmarkReport(projectId));
        overview.put("contextPacks", listContextPacks(projectId));
        overview.put("latestTasks", latestRetrievalTasks(projectId));
        overview.put("latestVersions", listIndexVersions(projectId).stream().limit(5).toList());
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

    public Map<String, Object> getQualityReport(String projectId) {
        projectService.getProject(projectId);
        Path file = qualityReportFile(projectId);
        Map<String, Object> report = readOptionalJson(file);
        report.put("path", relative(projectId, file));
        report.put("exists", Files.exists(file));
        report.put("updatedAt", Files.exists(file) ? modifiedAt(file) : "");
        if (!Files.exists(file)) {
            report.putIfAbsent("projectId", projectId);
            report.putIfAbsent("status", "missing");
            report.putIfAbsent("score", 0);
        }
        return report;
    }

    public Map<String, Object> getBenchmarkReport(String projectId) {
        projectService.getProject(projectId);
        Path file = benchmarkReportFile(projectId);
        Map<String, Object> report = readOptionalJson(file);
        report.put("path", relative(projectId, file));
        report.put("exists", Files.exists(file));
        report.put("updatedAt", Files.exists(file) ? modifiedAt(file) : "");
        if (!Files.exists(file)) {
            report.putIfAbsent("projectId", projectId);
            report.putIfAbsent("status", "missing");
            report.putIfAbsent("caseCount", 0);
        }
        return report;
    }

    public Task evaluateBenchmark(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.put("project_id", projectId);
        parameters.putIfAbsent("run_benchmark", true);

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

    public Map<String, Object> evaluateQuality(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> config = getConfig(projectId);
        Map<String, Object> bm25 = getIndexSummary(projectId, "bm25");
        Map<String, Object> vector = getIndexSummary(projectId, "vector");
        Map<String, Object> hybrid = getIndexSummary(projectId, "hybrid");
        Map<String, Object> rebuildReport = getRebuildReport(projectId);
        List<Map<String, Object>> contextPacks = listContextPacks(projectId);

        int minQualityScore = intValue(request == null ? null : request.get("min_quality_score"), 60);
        double maxBudgetUtilization = doubleValue(request == null ? null : request.get("max_budget_utilization"), 0.95);
        int maxSkippedDuplicates = intValue(request == null ? null : request.get("max_skipped_duplicates"), 3);

        int bm25Count = documentCount(bm25);
        int vectorCount = documentCount(vector);
        int hybridCount = documentCount(hybrid);
        Map<String, Object> latestPack = contextPacks.isEmpty() ? new LinkedHashMap<>() : contextPacks.get(0);
        Map<String, Object> quality = firstMap(
            hybrid.get("quality_evaluation"),
            rebuildReport.get("quality_evaluation"),
            latestPack.get("qualityEvaluation")
        );
        Map<String, Object> citationBudget = firstMap(
            hybrid.get("citation_budget"),
            rebuildReport.get("citation_budget"),
            latestPack.get("citationBudget")
        );
        Map<String, Object> budgetUsage = asMap(citationBudget.get("usage"));

        List<Map<String, Object>> checks = new ArrayList<>();
        addCheck(
            checks,
            "bm25_documents",
            bm25Count > 0 ? "pass" : "fail",
            "BM25 documents",
            bm25Count > 0 ? "BM25 index has " + bm25Count + " documents." : "BM25 index has no documents.",
            bm25Count > 0 ? null : "Import samples, generate analysis/memory artifacts, then rebuild retrieval indexes."
        );
        String vectorStatus = vectorCount > 0 && vectorCount == bm25Count ? "pass" : (vectorCount > 0 ? "warn" : "fail");
        addCheck(
            checks,
            "vector_documents",
            vectorStatus,
            "Vector documents",
            "Vector index has " + vectorCount + " documents; BM25 has " + bm25Count + ".",
            "Rebuild retrieval indexes if vector and BM25 counts drift."
        );
        addCheck(
            checks,
            "hybrid_summary",
            Boolean.TRUE.equals(hybrid.get("exists")) ? "pass" : "warn",
            "Hybrid summary",
            Boolean.TRUE.equals(hybrid.get("exists")) ? "Hybrid retrieval summary exists." : "Hybrid retrieval summary is missing.",
            "Generate a chapter context pack or run retrieval rebuild."
        );
        addCheck(
            checks,
            "rebuild_report",
            Boolean.TRUE.equals(rebuildReport.get("exists")) ? "pass" : "warn",
            "Rebuild report",
            Boolean.TRUE.equals(rebuildReport.get("exists")) ? "Latest rebuild report exists." : "No retrieval rebuild report was found.",
            "Run retrieval rebuild before delivery review."
        );
        int qualityScore = intValue(quality.get("score"), -1);
        addCheck(
            checks,
            "quality_score",
            qualityScore >= minQualityScore ? "pass" : (qualityScore >= 0 ? "warn" : "fail"),
            "Quality score",
            qualityScore >= 0 ? "Latest quality score is " + qualityScore + "." : "No quality evaluation was found.",
            "Improve query coverage/source diversity or rebuild indexes with more project artifacts."
        );
        int selectedCitations = intValue(
            firstPresent(budgetUsage.get("selected_result_count"), budgetUsage.get("selectedCitationCount")),
            0
        );
        addCheck(
            checks,
            "citation_selection",
            selectedCitations > 0 ? "pass" : "fail",
            "Citation selection",
            selectedCitations > 0 ? selectedCitations + " citations selected for prompt use." : "No citation was selected.",
            "Check retrieval query terms and citation budget caps."
        );
        double utilization = doubleValue(
            firstPresent(budgetUsage.get("context_utilization"), budgetUsage.get("retrieval_budget_utilization")),
            -1
        );
        addCheck(
            checks,
            "citation_budget",
            utilization >= 0 && utilization <= maxBudgetUtilization ? "pass" : (utilization >= 0 ? "warn" : "fail"),
            "Citation budget",
            utilization >= 0 ? "Budget utilization is " + Math.round(utilization * 100) + "%." : "No citation budget usage was found.",
            "Reduce max result length or increase retrieval/context budget."
        );
        int skippedDuplicates = intValue(
            firstPresent(budgetUsage.get("skipped_duplicates"), budgetUsage.get("skippedDuplicates")),
            0
        );
        addCheck(
            checks,
            "duplicate_budget",
            skippedDuplicates <= maxSkippedDuplicates ? "pass" : "warn",
            "Duplicate pruning",
            skippedDuplicates + " duplicate retrieval results were skipped.",
            "Review repeated sample chunks or duplicated Markdown artifacts."
        );
        addCheck(
            checks,
            "context_packs",
            contextPacks.isEmpty() ? "warn" : "pass",
            "Context packs",
            contextPacks.isEmpty() ? "No context pack was found." : contextPacks.size() + " context packs are available.",
            "Generate at least one chapter context pack for downstream writing validation."
        );

        int score = calculateScore(checks);
        String status = score >= 75 ? "good" : (score >= 50 ? "needs_review" : "poor");
        Set<String> warnings = new LinkedHashSet<>();
        Set<String> recommendations = new LinkedHashSet<>();
        collectStrings(quality.get("warnings"), warnings);
        collectStrings(citationBudget.get("warnings"), warnings);
        collectStrings(quality.get("recommendations"), recommendations);
        collectStrings(citationBudget.get("recommendations"), recommendations);
        for (Map<String, Object> check : checks) {
            if (!"pass".equals(check.get("status"))) {
                warnings.add(String.valueOf(check.get("message")));
                Object recommendation = check.get("recommendation");
                if (recommendation != null && !String.valueOf(recommendation).isBlank()) {
                    recommendations.add(String.valueOf(recommendation));
                }
            }
        }

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("bm25DocumentCount", bm25Count);
        coverage.put("vectorDocumentCount", vectorCount);
        coverage.put("hybridDocumentCount", hybridCount);
        coverage.put("contextPackCount", contextPacks.size());
        coverage.put("configExists", config.get("exists"));
        coverage.put("rebuildReportExists", rebuildReport.get("exists"));

        Map<String, Object> sourceBalance = new LinkedHashMap<>();
        sourceBalance.put("bm25SourceCounts", asMap(bm25.get("source_counts")));
        sourceBalance.put("citationSourceCounts", asMap(budgetUsage.get("source_type_distribution")));
        sourceBalance.put("latestContextPackSources", latestPack.getOrDefault("sources", Map.of()));

        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("config", artifactRef(projectId, configFile(projectId)));
        artifacts.put("bm25", artifactRef(projectId, indexDir(projectId, "bm25").resolve("index_summary.json")));
        artifacts.put("vector", artifactRef(projectId, indexDir(projectId, "vector").resolve("index_summary.json")));
        artifacts.put("hybrid", artifactRef(projectId, indexDir(projectId, "hybrid").resolve("index_summary.json")));
        artifacts.put("rebuildReport", artifactRef(projectId, projectRoot(projectId).resolve("indexes").resolve("retrieval_index_report.json")));
        artifacts.put("qualityReport", artifactRef(projectId, qualityReportFile(projectId)));
        artifacts.put("benchmarkReport", artifactRef(projectId, benchmarkReportFile(projectId)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("projectId", projectId);
        report.put("evaluatedAt", LocalDateTime.now().toString());
        report.put("status", status);
        report.put("score", score);
        report.put("coverage", coverage);
        report.put("quality", quality);
        report.put("citationBudget", citationBudget);
        report.put("sourceBalance", sourceBalance);
        report.put("checks", checks);
        report.put("warnings", new ArrayList<>(warnings));
        report.put("recommendations", new ArrayList<>(recommendations));
        report.put("artifacts", artifacts);
        report.put("thresholds", Map.of(
            "minQualityScore", minQualityScore,
            "maxBudgetUtilization", maxBudgetUtilization,
            "maxSkippedDuplicates", maxSkippedDuplicates
        ));
        writeJson(qualityReportFile(projectId), report);
        return getQualityReport(projectId);
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
                    "use_model_embeddings",
                    "vector_backend",
                    "graph_relation",
                    "graph_hops",
                    "vector_filters",
                    "keyword_filters",
                    "top_k",
                    "max_context_chars",
                    "max_memory_chars",
                    "max_character_memory_chars",
                    "max_graph_nodes",
                    "max_retrieval_results",
                    "max_retrieval_chars",
                    "max_result_chars",
                    "max_sample_quote_chars",
                    "max_results_per_source_type")) {
                if (request.containsKey(key)) {
                    config.put(key, request.get(key));
                }
            }
        }

        writeJson(configFile(projectId), config);
        return getConfig(projectId);
    }

    public Map<String, Object> invalidateCaches(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> options = request == null ? Map.of() : request;
        boolean clearContextPacks = booleanValue(firstPresent(
            options.get("clear_context_packs"),
            options.get("clearContextPacks")
        ), true);
        boolean clearQualityReport = booleanValue(firstPresent(
            options.get("clear_quality_report"),
            options.get("clearQualityReport")
        ), true);
        boolean clearHybridSummary = booleanValue(firstPresent(
            options.get("clear_hybrid_summary"),
            options.get("clearHybridSummary")
        ), true);
        boolean clearRebuildReport = booleanValue(firstPresent(
            options.get("clear_rebuild_report"),
            options.get("clearRebuildReport")
        ), true);
        boolean clearIndexSummaries = booleanValue(firstPresent(
            options.get("clear_index_summaries"),
            options.get("clearIndexSummaries")
        ), false);

        LocalDateTime invalidatedAt = LocalDateTime.now();
        Path versionFile = versionsDir(projectId).resolve(
            "retrieval_invalidation_" + invalidatedAt.format(VERSION_TIMESTAMP) + ".json"
        );

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("projectId", projectId);
        snapshot.put("id", stripSuffix(versionFile.getFileName().toString(), ".json"));
        snapshot.put("type", "cache_invalidation");
        snapshot.put("createdAt", invalidatedAt.toString());
        snapshot.put("invalidatedAt", invalidatedAt.toString());
        snapshot.put("actor", stringValue(firstPresent(options.get("actor"), options.get("user")), "human"));
        snapshot.put("reason", stringValue(options.get("reason"), "manual retrieval cache invalidation"));
        snapshot.put("options", Map.of(
            "clearContextPacks", clearContextPacks,
            "clearQualityReport", clearQualityReport,
            "clearHybridSummary", clearHybridSummary,
            "clearRebuildReport", clearRebuildReport,
            "clearIndexSummaries", clearIndexSummaries
        ));
        snapshot.put("config", getConfig(projectId));
        snapshot.put("indexes", getIndexSummaries(projectId));
        snapshot.put("qualityReport", getQualityReport(projectId));
        snapshot.put("contextPacks", listContextPacks(projectId));
        snapshot.put("versionPath", relative(projectId, versionFile));
        writeJson(versionFile, snapshot);

        List<Map<String, Object>> deleted = new ArrayList<>();
        if (clearContextPacks) {
            deleteContextPacks(projectId, deleted);
        }
        if (clearQualityReport) {
            deleteFileIfExists(projectId, qualityReportFile(projectId), "quality_report", deleted);
        }
        if (clearHybridSummary) {
            deleteFileIfExists(projectId, indexDir(projectId, "hybrid").resolve("index_summary.json"), "hybrid_summary", deleted);
        }
        if (clearRebuildReport) {
            deleteFileIfExists(projectId, projectRoot(projectId).resolve("indexes").resolve("retrieval_index_report.json"), "rebuild_report", deleted);
        }
        if (clearIndexSummaries) {
            for (String indexType : INDEX_TYPES) {
                deleteFileIfExists(
                    projectId,
                    indexDir(projectId, indexType).resolve("index_summary.json"),
                    indexType + "_summary",
                    deleted
                );
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "invalidated");
        response.put("projectId", projectId);
        response.put("versionId", stripSuffix(versionFile.getFileName().toString(), ".json"));
        response.put("versionPath", relative(projectId, versionFile));
        response.put("deletedCount", deleted.size());
        response.put("deleted", deleted);
        response.put("remainingContextPackCount", listContextPacks(projectId).size());
        response.put("invalidatedAt", invalidatedAt.toString());
        return response;
    }

    public Task rebuildIndexes(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.put("project_id", projectId);
        Map<String, Object> version = createIndexVersionIfPresent(
            projectId,
            "before_retrieval_index_rebuild",
            stringValue(firstPresent(parameters.get("actor"), parameters.get("user")), "system"),
            "Automatic snapshot before retrieval index rebuild"
        );
        if (!version.isEmpty()) {
            parameters.put("previous_retrieval_version_id", version.get("id"));
            parameters.put("previous_retrieval_version_path", version.get("path"));
        }

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

    public Map<String, Object> createIndexVersion(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> options = request == null ? Map.of() : request;
        return createIndexVersion(
            projectId,
            stringValue(options.get("reason"), "manual_retrieval_index_snapshot"),
            stringValue(firstPresent(options.get("actor"), options.get("user")), "human"),
            stringValue(options.get("note"), "")
        );
    }

    public List<Map<String, Object>> listIndexVersions(String projectId) {
        projectService.getProject(projectId);
        Path versionsDir = versionsDir(projectId);
        if (!Files.exists(versionsDir)) {
            return List.of();
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        try (var stream = Files.list(versionsDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        versions.add(indexVersionSummary(projectId, path, readOptionalJson(path)));
                    } catch (Exception ignored) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                        item.put("projectId", projectId);
                        item.put("path", relative(projectId, path));
                        item.put("createdAt", modifiedAt(path));
                        item.put("updatedAt", modifiedAt(path));
                        versions.add(item);
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read retrieval index versions", e);
        }
        versions.sort(Comparator.comparing(version -> String.valueOf(version.get("createdAt")), Comparator.reverseOrder()));
        return versions;
    }

    public Map<String, Object> getIndexVersion(String projectId, String versionId) {
        projectService.getProject(projectId);
        validateId(versionId, "versionId");
        Path versionFile = versionsDir(projectId).resolve(versionId + ".json");
        if (!Files.exists(versionFile)) {
            throw new ResourceNotFoundException("Retrieval index version does not exist: " + versionId);
        }
        Map<String, Object> version = readOptionalJson(versionFile);
        version.put("id", versionId);
        version.put("path", relative(projectId, versionFile));
        version.put("updatedAt", modifiedAt(versionFile));
        version.put("summary", indexVersionSummary(projectId, versionFile, version));
        return version;
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
                    item.put("qualityEvaluation", data.get("quality_evaluation"));
                    item.put("citationBudget", data.get("citation_budget"));
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

    private void addCheck(
            List<Map<String, Object>> checks,
            String key,
            String status,
            String title,
            String message,
            String recommendation) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("key", key);
        check.put("status", status);
        check.put("title", title);
        check.put("message", message);
        if (recommendation != null && !"pass".equals(status)) {
            check.put("recommendation", recommendation);
        }
        checks.add(check);
    }

    private int calculateScore(List<Map<String, Object>> checks) {
        if (checks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map<String, Object> check : checks) {
            String status = String.valueOf(check.get("status"));
            if ("pass".equals(status)) {
                total += 100;
            } else if ("warn".equals(status)) {
                total += 60;
            }
        }
        return Math.max(0, Math.min(100, Math.round((float) total / checks.size())));
    }

    @SafeVarargs
    private final Map<String, Object> firstMap(Object... values) {
        for (Object value : values) {
            Map<String, Object> map = asMap(value);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return new LinkedHashMap<>();
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private void collectStrings(Object value, Set<String> target) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    target.add(String.valueOf(item));
                }
            }
            return;
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            target.add(String.valueOf(value));
        }
    }

    private Map<String, Object> artifactRef(String projectId, Path path) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("path", relative(projectId, path));
        artifact.put("exists", Files.exists(path));
        artifact.put("updatedAt", Files.exists(path) ? modifiedAt(path) : "");
        return artifact;
    }

    private int documentCount(Map<String, Object> summary) {
        if (summary == null) {
            return 0;
        }
        for (String key : List.of("document_count", "documentCount", "vector_count", "returned_count", "total_documents")) {
            int value = intValue(summary.get(key), -1);
            if (value >= 0) {
                return value;
            }
        }
        Map<String, Object> stats = asMap(summary.get("stats"));
        for (String key : List.of("document_count", "documentCount", "vector_count", "returned_count", "total_documents")) {
            int value = intValue(stats.get(key), -1);
            if (value >= 0) {
                return value;
            }
        }
        Object documents = summary.get("documents");
        if (documents instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString().trim().toLowerCase();
        if (text.isBlank()) {
            return defaultValue;
        }
        return switch (text) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> defaultValue;
        };
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return value.toString();
    }

    private Map<String, Object> createIndexVersionIfPresent(
            String projectId,
            String reason,
            String actor,
            String note) {
        try {
            return createIndexVersion(projectId, reason, actor, note);
        } catch (ResourceNotFoundException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> createIndexVersion(
            String projectId,
            String reason,
            String actor,
            String note) {
        LocalDateTime createdAt = LocalDateTime.now();
        String versionId = "retrieval_index_" + createdAt.format(VERSION_TIMESTAMP);
        Path versionFile = versionsDir(projectId).resolve(versionId + ".json");

        Map<String, Object> indexes = getIndexSummaries(projectId);
        Map<String, Object> qualityReport = getQualityReport(projectId);
        List<Map<String, Object>> contextPacks = listContextPacks(projectId);
        if (!hasIndexVersionContent(indexes, qualityReport, contextPacks)) {
            throw new ResourceNotFoundException("Retrieval index artifacts do not exist, rebuild indexes first");
        }

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", versionId);
        version.put("type", "index_snapshot");
        version.put("projectId", projectId);
        version.put("reason", reason);
        version.put("actor", actor);
        version.put("note", note);
        version.put("createdAt", createdAt.toString());
        version.put("config", getConfig(projectId));
        version.put("indexes", indexes);
        version.put("qualityReport", qualityReport);
        version.put("benchmarkReport", getBenchmarkReport(projectId));
        version.put("contextPacks", contextPacks);
        version.put("documentCounts", indexDocumentCounts(indexes));
        version.put("artifactRefs", Map.of(
            "config", artifactRef(projectId, configFile(projectId)),
            "bm25", artifactRef(projectId, indexDir(projectId, "bm25").resolve("index_summary.json")),
            "vector", artifactRef(projectId, indexDir(projectId, "vector").resolve("index_summary.json")),
            "hybrid", artifactRef(projectId, indexDir(projectId, "hybrid").resolve("index_summary.json")),
            "rebuildReport", artifactRef(projectId, projectRoot(projectId).resolve("indexes").resolve("retrieval_index_report.json")),
            "qualityReport", artifactRef(projectId, qualityReportFile(projectId)),
            "benchmarkReport", artifactRef(projectId, benchmarkReportFile(projectId))
        ));
        version.put("versionPath", relative(projectId, versionFile));
        writeJson(versionFile, version);

        Map<String, Object> response = indexVersionSummary(projectId, versionFile, version);
        response.put("documentCounts", version.get("documentCounts"));
        response.put("artifactRefs", version.get("artifactRefs"));
        return response;
    }

    private boolean hasIndexVersionContent(
            Map<String, Object> indexes,
            Map<String, Object> qualityReport,
            List<Map<String, Object>> contextPacks) {
        for (String indexType : INDEX_TYPES) {
            if (Boolean.TRUE.equals(asMap(indexes.get(indexType)).get("exists"))) {
                return true;
            }
        }
        if (Boolean.TRUE.equals(asMap(indexes.get("rebuildReport")).get("exists"))) {
            return true;
        }
        if (Boolean.TRUE.equals(qualityReport.get("exists"))) {
            return true;
        }
        return contextPacks != null && !contextPacks.isEmpty();
    }

    private Map<String, Object> indexVersionSummary(String projectId, Path versionFile, Map<String, Object> version) {
        Map<String, Object> item = new LinkedHashMap<>();
        String id = String.valueOf(version.getOrDefault("id", stripSuffix(versionFile.getFileName().toString(), ".json")));
        Map<String, Object> indexes = asMap(version.get("indexes"));
        Map<String, Object> qualityReport = asMap(version.get("qualityReport"));
        Map<String, Object> hybridQuality = asMap(asMap(indexes.get("hybrid")).get("quality_evaluation"));

        item.put("id", id);
        item.put("type", version.getOrDefault("type", id.startsWith("retrieval_invalidation_") ? "cache_invalidation" : "index_snapshot"));
        item.put("projectId", version.getOrDefault("projectId", projectId));
        item.put("reason", version.get("reason"));
        item.put("actor", version.get("actor"));
        item.put("note", version.get("note"));
        item.put("createdAt", firstPresent(version.get("createdAt"), version.get("invalidatedAt"), modifiedAt(versionFile)));
        item.put("path", relative(projectId, versionFile));
        item.put("updatedAt", modifiedAt(versionFile));
        item.put("bm25DocumentCount", documentCount(asMap(indexes.get("bm25"))));
        item.put("vectorDocumentCount", documentCount(asMap(indexes.get("vector"))));
        item.put("hybridDocumentCount", documentCount(asMap(indexes.get("hybrid"))));
        item.put("contextPackCount", listSize(version.get("contextPacks")));
        item.put("qualityScore", firstPresent(qualityReport.get("score"), hybridQuality.get("score")));
        item.put("qualityStatus", firstPresent(qualityReport.get("status"), hybridQuality.get("status")));
        item.put("rebuildReportExists", Boolean.TRUE.equals(asMap(indexes.get("rebuildReport")).get("exists")));
        if (version.containsKey("deletedCount")) {
            item.put("deletedCount", version.get("deletedCount"));
        }
        return item;
    }

    private Map<String, Object> indexDocumentCounts(Map<String, Object> indexes) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String indexType : INDEX_TYPES) {
            counts.put(indexType, documentCount(asMap(indexes.get(indexType))));
        }
        return counts;
    }

    private int listSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private void deleteContextPacks(String projectId, List<Map<String, Object>> deleted) {
        Path contextDir = indexDir(projectId, "bm25").resolve("context_packs");
        if (!Files.exists(contextDir)) {
            return;
        }
        List<Path> contextFiles;
        try (var stream = Files.list(contextDir)) {
            contextFiles = stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to invalidate retrieval context packs", e);
        }
        for (Path path : contextFiles) {
            deleteFileIfExists(projectId, path, "context_pack", deleted);
        }
        deleteDirectoryIfEmpty(contextDir);
    }

    private void deleteFileIfExists(String projectId, Path file, String type, List<Map<String, Object>> deleted) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("path", relative(projectId, file));
            item.put("sizeBytes", Files.size(file));
            item.put("updatedAt", modifiedAt(file));
            Files.delete(file);
            deleted.add(item);
        } catch (IOException e) {
            throw new RuntimeException("Failed to invalidate retrieval artifact: " + file.getFileName(), e);
        }
    }

    private void deleteDirectoryIfEmpty(Path dir) {
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to clean empty retrieval cache directory: " + dir.getFileName(), e);
        }
    }

    private Path indexDir(String projectId, String indexType) {
        return projectRoot(projectId).resolve("indexes").resolve(indexType);
    }

    private Path configFile(String projectId) {
        return projectRoot(projectId).resolve("indexes").resolve("retrieval_config.json");
    }

    private Path qualityReportFile(String projectId) {
        return projectRoot(projectId).resolve("indexes").resolve("retrieval_quality_report.json");
    }

    private Path benchmarkReportFile(String projectId) {
        return projectRoot(projectId).resolve("indexes").resolve("retrieval_benchmark_report.json");
    }

    private Path versionsDir(String projectId) {
        return projectRoot(projectId).resolve("indexes").resolve("versions");
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
        config.put("use_model_embeddings", false);
        config.put("vector_backend", "auto");
        config.put("graph_relation", null);
        config.put("graph_hops", 2);
        config.put("vector_filters", new LinkedHashMap<>());
        config.put("keyword_filters", new LinkedHashMap<>());
        config.put("top_k", 12);
        config.put("max_context_chars", 6000);
        config.put("max_memory_chars", 1200);
        config.put("max_character_memory_chars", 1000);
        config.put("max_graph_nodes", 8);
        config.put("max_retrieval_results", 8);
        config.put("max_retrieval_chars", 2400);
        config.put("max_result_chars", 220);
        config.put("max_sample_quote_chars", 80);
        config.put("max_results_per_source_type", 4);
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
