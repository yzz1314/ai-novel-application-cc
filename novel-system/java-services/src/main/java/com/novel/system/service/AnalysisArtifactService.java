package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.Sample;
import com.novel.system.entity.Task;
import com.novel.system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AnalysisArtifactService {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ProjectService projectService;
    private final SampleService sampleService;
    private final TaskExecutorService taskExecutorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    public Map<String, Object> getProjectAnalysisStatus(String projectId) {
        projectService.getProject(projectId);
        List<Sample> samples = sampleService.listSamplesByProject(projectId);
        List<Task> tasks = taskExecutorService.listTasksByProject(projectId);

        long uploaded = samples.stream().filter(sample -> sample.getStatus() == Sample.SampleStatus.UPLOADED).count();
        long chunked = samples.stream().filter(sample -> sample.getStatus() == Sample.SampleStatus.CHUNKED).count();
        long analyzed = samples.stream().filter(sample -> sample.getStatus() == Sample.SampleStatus.ANALYZED).count();
        long runningTasks = tasks.stream()
            .filter(task -> task.getStatus() == Task.TaskStatus.PENDING || task.getStatus() == Task.TaskStatus.RUNNING)
            .count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("sampleCount", samples.size());
        response.put("uploadedSamples", uploaded);
        response.put("chunkedSamples", chunked);
        response.put("analyzedSamples", analyzed);
        response.put("runningTasks", runningTasks);
        response.put("hasCrossBookReport", Files.exists(crossBookReport(projectId)));
        response.put("hasTechniqueSummary", Files.exists(techniqueSummary(projectId)));
        response.put("samples", samples.stream().map(sample -> sampleArtifactSummary(projectId, sample)).toList());
        response.put("latestTasks", tasks.stream()
            .sorted(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(10)
            .map(this::taskSummary)
            .toList());
        return response;
    }

    public Map<String, Object> getProjectAnalysisReport(String projectId) {
        projectService.getProject(projectId);
        List<Map<String, Object>> perBookReports = sampleService.listSamplesByProject(projectId).stream()
            .map(sample -> {
                Map<String, Object> report = sampleArtifactSummary(projectId, sample);
                report.put("bookReport", readOptionalText(bookReport(projectId, sample.getId()))
                    .map(content -> reportContent(projectId, bookReport(projectId, sample.getId()), content))
                    .orElse(null));
                return report;
            })
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("perBookReports", perBookReports);
        response.put("crossBookReport", getOptionalCrossBookReport(projectId).orElse(null));
        return response;
    }

    public Map<String, Object> getSampleArtifacts(String projectId, String sampleId) {
        Sample sample = getProjectSample(projectId, sampleId);
        Map<String, Object> response = sampleArtifactSummary(projectId, sample);
        response.put("manifest", readOptionalJson(manifestFile(projectId, sampleId)).orElse(null));
        response.put("analysisSummary", readOptionalJson(analysisSummary(projectId, sampleId)).orElse(null));
        response.put("coverageReport", readOptionalJson(coverageReport(projectId, sampleId)).map(this::camelizeMap).orElse(null));
        response.put("latestTasks", taskExecutorService.listTasksByProject(projectId).stream()
            .filter(task -> sampleId.equals(stringValue(taskValue(task, "sample_id"))))
            .sorted(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(10)
            .map(this::taskSummary)
            .toList());
        return response;
    }

    public Map<String, Object> getSampleCoverage(String projectId, String sampleId) {
        getProjectSample(projectId, sampleId);
        Path file = coverageReport(projectId, sampleId);
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("覆盖率报告不存在: " + sampleId);
        }
        Map<String, Object> response = camelizeMap(readJson(file));
        response.put("path", relative(projectId, file));
        response.put("updatedAt", modifiedAt(file));
        return response;
    }

    public Map<String, Object> getSampleAnalysisIssues(String projectId, String sampleId) {
        getProjectSample(projectId, sampleId);
        Map<String, Object> coverage = readOptionalJson(coverageReport(projectId, sampleId))
            .map(this::camelizeMap)
            .orElseGet(LinkedHashMap::new);
        Map<String, Object> analysisCoverage = mapValue(coverage.get("analysisCoverage"));
        Map<String, Object> repairQueue = mapValue(coverage.get("repairQueue"));
        Map<String, Map<String, Object>> repairItems = repairItemsByChunkId(repairQueue);

        List<Map<String, Object>> rawIssues = new ArrayList<>();
        asList(analysisCoverage.get("missingAnalysisChunks")).forEach(chunkId -> {
            String normalizedChunkId = stringValue(chunkId);
            if (!normalizedChunkId.isBlank()) {
                rawIssues.add(analysisIssue(projectId, sampleId, normalizedChunkId, "missing", repairItems.get(normalizedChunkId), null));
            }
        });
        asList(analysisCoverage.get("failedChunks")).stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .forEach(failed -> {
                String chunkId = firstString(failed, "chunkId", "chunk_id", "id");
                if (!chunkId.isBlank()) {
                    rawIssues.add(analysisIssue(projectId, sampleId, chunkId, "failed", repairItems.get(chunkId), failed));
                }
            });
        List<Map<String, Object>> issues = sortIssuesByRepairQueue(rawIssues, repairQueue);

        List<String> issueChunkIds = issues.stream()
            .map(issue -> stringValue(issue.get("chunkId")))
            .filter(chunkId -> !chunkId.isBlank())
            .distinct()
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("sampleId", sampleId);
        response.put("status", coverage.getOrDefault("status", issues.isEmpty() ? "passed" : "failed"));
        response.put("coverageReportPath", relativeIfExists(projectId, coverageReport(projectId, sampleId)));
        response.put("repairQueuePath", coverage.get("repairQueuePath"));
        response.put("totalCount", issues.size());
        response.put("missingCount", issues.stream().filter(issue -> "missing".equals(issue.get("type"))).count());
        response.put("failedCount", issues.stream().filter(issue -> "failed".equals(issue.get("type"))).count());
        response.put("issueChunkIds", issueChunkIds);
        response.put("issues", issues);
        response.put("repairQueue", repairQueue);
        return response;
    }

    public Task checkSampleCoverage(String projectId, String sampleId) {
        return checkSampleCoverage(projectId, sampleId, Map.of());
    }

    public Task checkSampleCoverage(String projectId, String sampleId, Map<String, Object> request) {
        getProjectSample(projectId, sampleId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.put("sample_id", sampleId);
        Map<String, Object> refs = Map.of("sample_id", sampleId);
        Task task = taskExecutorService.createTask(
            projectId,
            "coverage_check",
            "coverage_check",
            refs,
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    public Task repairSampleAnalysis(String projectId, String sampleId, Map<String, Object> request) {
        getProjectSample(projectId, sampleId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.put("sample_id", sampleId);
        Map<String, Object> refs = Map.of("sample_id", sampleId);
        Task task = taskExecutorService.createTask(
            projectId,
            "analysis_repair",
            "analysis_repair",
            refs,
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    public Map<String, Object> getSampleManifest(String projectId, String sampleId) {
        getProjectSample(projectId, sampleId);
        Path file = manifestFile(projectId, sampleId);
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("样本manifest不存在: " + sampleId);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("path", relative(projectId, file));
        response.put("updatedAt", modifiedAt(file));
        response.put("content", readJson(file));
        return response;
    }

    public List<Map<String, Object>> listSampleChunks(String projectId, String sampleId) {
        getProjectSample(projectId, sampleId);
        Path dir = chunksDir(projectId, sampleId);
        if (!Files.exists(dir)) {
            return List.of();
        }

        try (var stream = Files.list(dir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .sorted()
                .map(path -> chunkResponse(projectId, path, false))
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取样本分块失败", e);
        }
    }

    public Map<String, Object> getSampleChunk(String projectId, String sampleId, String chunkId) {
        getProjectSample(projectId, sampleId);
        validateId(chunkId, "chunkId");
        Path file = chunksDir(projectId, sampleId).resolve(chunkId + ".json");
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("样本分块不存在: " + chunkId);
        }
        return chunkResponse(projectId, file, true);
    }

    public Map<String, Object> getSampleAnalysis(String projectId, String sampleId) {
        getProjectSample(projectId, sampleId);
        Path dir = analysisDir(projectId, sampleId);
        Path legacyDir = legacyAnalysisDir(projectId, sampleId);
        if (!Files.exists(dir) && Files.exists(legacyDir)) {
            dir = legacyDir;
        }

        List<Map<String, Object>> chunks = new ArrayList<>();
        if (Files.exists(dir)) {
            try (var stream = Files.list(dir)) {
                chunks = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith("_analysis.json"))
                    .sorted()
                    .map(path -> analysisFileResponse(projectId, path, false))
                    .toList();
            } catch (IOException e) {
                throw new RuntimeException("读取逐块分析失败", e);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sampleId", sampleId);
        response.put("summary", readOptionalJson(dir.resolve("summary.json")).orElse(null));
        response.put("chunks", chunks);
        return response;
    }

    public Map<String, Object> getChunkAnalysis(String projectId, String sampleId, String chunkId) {
        getProjectSample(projectId, sampleId);
        validateId(chunkId, "chunkId");
        Path file = analysisDir(projectId, sampleId).resolve(chunkId + "_analysis.json");
        if (!Files.exists(file)) {
            file = legacyAnalysisDir(projectId, sampleId).resolve(chunkId + "_analysis.json");
        }
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("分块分析不存在: " + chunkId);
        }
        return analysisFileResponse(projectId, file, true);
    }

    public Map<String, Object> getBookReport(String projectId, String sampleId) {
        getProjectSample(projectId, sampleId);
        Path file = bookReport(projectId, sampleId);
        if (!Files.exists(file)) {
            file = legacyBookReport(projectId, sampleId);
        }
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("单书报告不存在: " + sampleId);
        }
        return reportContent(projectId, file, readText(file));
    }

    public Map<String, Object> getCrossBookReport(String projectId) {
        projectService.getProject(projectId);
        return getOptionalCrossBookReport(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("跨书归纳报告不存在"));
    }

    private Optional<Map<String, Object>> getOptionalCrossBookReport(String projectId) {
        Path report = crossBookReport(projectId);
        if (!Files.exists(report)) {
            report = projectRoot(projectId).resolve("cross_book_synthesis.md");
        }
        if (!Files.exists(report)) {
            return Optional.empty();
        }

        Map<String, Object> response = reportContent(projectId, report, readText(report));
        readOptionalJson(techniqueSummary(projectId)).ifPresent(summary -> response.put("techniqueSummary", summary));
        return Optional.of(response);
    }

    private Map<String, Object> sampleArtifactSummary(String projectId, Sample sample) {
        String sampleId = sample.getId();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleId", sampleId);
        summary.put("title", sample.getTitle());
        summary.put("fileName", sample.getFileName());
        summary.put("status", sample.getStatus().name());
        summary.put("totalChars", sample.getTotalChars());
        summary.put("totalChapters", sample.getTotalChapters());
        summary.put("hasManifest", Files.exists(manifestFile(projectId, sampleId)));
        summary.put("chunkCount", countFiles(chunksDir(projectId, sampleId), ".json"));
        int analysisCount = countFiles(analysisDir(projectId, sampleId), "_analysis.json");
        if (analysisCount == 0) {
            analysisCount = countFiles(legacyAnalysisDir(projectId, sampleId), "_analysis.json");
        }
        summary.put("analysisCount", analysisCount);
        summary.put("hasAnalysisSummary", Files.exists(analysisSummary(projectId, sampleId))
            || Files.exists(legacyAnalysisDir(projectId, sampleId).resolve("summary.json")));
        summary.put("hasCoverageReport", Files.exists(coverageReport(projectId, sampleId)));
        summary.put("hasBookReport", Files.exists(bookReport(projectId, sampleId)) || Files.exists(legacyBookReport(projectId, sampleId)));
        summary.put("manifestPath", relativeIfExists(projectId, manifestFile(projectId, sampleId)));
        summary.put("coverageReportPath", relativeIfExists(projectId, coverageReport(projectId, sampleId)));
        summary.put("bookReportPath", relativeIfExists(projectId, bookReport(projectId, sampleId)));
        return summary;
    }

    private Map<String, Object> chunkResponse(String projectId, Path file, boolean includeContent) {
        Map<String, Object> chunk = camelizeMap(readJson(file));
        chunk.put("id", chunk.getOrDefault("id", stripSuffix(file.getFileName().toString(), ".json")));
        chunk.put("path", relative(projectId, file));
        chunk.put("updatedAt", modifiedAt(file));
        if (!includeContent) {
            Object text = chunk.remove("text");
            Object content = chunk.remove("content");
            String preview = stringValue(content != null ? content : text);
            chunk.put("preview", preview.length() > 160 ? preview.substring(0, 160) : preview);
        }
        return chunk;
    }

    private Map<String, Object> analysisFileResponse(String projectId, Path file, boolean includeContent) {
        Map<String, Object> analysis = camelizeMap(readJson(file));
        analysis.put("id", stripSuffix(file.getFileName().toString(), "_analysis.json"));
        analysis.put("path", relative(projectId, file));
        analysis.put("updatedAt", modifiedAt(file));
        if (!includeContent) {
            analysis.remove("analysis");
        }
        return analysis;
    }

    private Map<String, Object> analysisIssue(
            String projectId,
            String sampleId,
            String chunkId,
            String type,
            Map<String, Object> repairItem,
            Map<?, ?> failedItem) {
        Path chunkPath = chunksDir(projectId, sampleId).resolve(chunkId + ".json");
        Path analysisPath = analysisDir(projectId, sampleId).resolve(chunkId + "_analysis.json");
        if (!Files.exists(analysisPath)) {
            analysisPath = legacyAnalysisDir(projectId, sampleId).resolve(chunkId + "_analysis.json");
        }

        Map<String, Object> chunk = Files.exists(chunkPath) ? chunkResponse(projectId, chunkPath, false) : new LinkedHashMap<>();
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("key", type + "-" + chunkId);
        issue.put("chunkId", chunkId);
        issue.put("type", type);
        issue.put("status", "failed".equals(type) ? "失败" : "缺失");
        issue.put("reason", repairItem != null ? repairItem.getOrDefault("reason", defaultIssueReason(type)) : defaultIssueReason(type));
        issue.put("priority", repairItem != null ? repairItem.getOrDefault("priority", defaultIssuePriority(type)) : defaultIssuePriority(type));
        issue.put("error", issueError(type, repairItem, failedItem));
        issue.put("repairable", true);
        issue.put("chunkPath", Files.exists(chunkPath) ? relative(projectId, chunkPath) : null);
        issue.put("analysisPath", Files.exists(analysisPath) ? relative(projectId, analysisPath) : null);
        issue.put("chunkIndex", chunk.get("chunkIndex"));
        issue.put("chapterIndex", chunk.get("chapterIndex"));
        issue.put("chapterRange", chunk.get("chapterRange"));
        issue.put("startOffset", chunk.get("startOffset"));
        issue.put("endOffset", chunk.get("endOffset"));
        issue.put("charCount", chunk.get("charCount"));
        issue.put("preview", chunk.get("preview"));
        issue.put("repairItem", repairItem);
        return issue;
    }

    private Map<String, Map<String, Object>> repairItemsByChunkId(Map<String, Object> repairQueue) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        asList(repairQueue.get("items")).stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .forEach(raw -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) raw;
                String chunkId = firstString(item, "chunkId", "chunk_id");
                if (!chunkId.isBlank()) {
                    result.put(chunkId, item);
                }
            });
        return result;
    }

    private List<Map<String, Object>> sortIssuesByRepairQueue(List<Map<String, Object>> issues, Map<String, Object> repairQueue) {
        List<?> queueChunkIds = asList(repairQueue.get("chunkIds"));
        if (queueChunkIds.isEmpty()) {
            queueChunkIds = asList(repairQueue.get("chunk_ids"));
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < queueChunkIds.size(); i++) {
            order.put(stringValue(queueChunkIds.get(i)), i);
        }
        return issues.stream()
            .sorted(Comparator
                .comparingInt((Map<String, Object> issue) -> order.getOrDefault(stringValue(issue.get("chunkId")), Integer.MAX_VALUE))
                .thenComparing(issue -> stringValue(issue.get("chunkId"))))
            .toList();
    }

    private String issueError(String type, Map<String, Object> repairItem, Map<?, ?> failedItem) {
        String repairError = repairItem != null ? stringValue(repairItem.get("error")) : "";
        if (!repairError.isBlank()) {
            return repairError;
        }
        if (failedItem != null) {
            String failedError = firstString(failedItem, "error", "message");
            if (!failedError.isBlank()) {
                return failedError;
            }
        }
        return "failed".equals(type) ? "分析结果文件失败或不可读" : "未生成逐块分析结果";
    }

    private String defaultIssueReason(String type) {
        return "failed".equals(type) ? "failed_analysis" : "missing_analysis";
    }

    private String defaultIssuePriority(String type) {
        return "failed".equals(type) ? "critical" : "high";
    }

    private Map<String, Object> reportContent(String projectId, Path file, String content) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("path", relative(projectId, file));
        response.put("content", content);
        response.put("length", content.length());
        response.put("updatedAt", modifiedAt(file));
        return response;
    }

    private Map<String, Object> taskSummary(Task task) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", task.getId());
        summary.put("taskType", task.getTaskType());
        summary.put("agentName", task.getAgentName());
        summary.put("status", task.getStatus().name());
        summary.put("inputRefs", task.getInputRefs());
        summary.put("outputRefs", task.getOutputRefs());
        summary.put("errors", task.getErrors());
        summary.put("warnings", task.getWarnings());
        summary.put("createdAt", task.getCreatedAt());
        summary.put("startedAt", task.getStartedAt());
        summary.put("finishedAt", task.getFinishedAt());
        return summary;
    }

    private Sample getProjectSample(String projectId, String sampleId) {
        projectService.getProject(projectId);
        validateId(sampleId, "sampleId");
        Sample sample = sampleService.getSample(sampleId);
        if (!projectId.equals(sample.getProjectId())) {
            throw new ResourceNotFoundException("样本不属于当前项目: " + sampleId);
        }
        return sample;
    }

    private Object taskValue(Task task, String key) {
        if (task.getInputRefs() != null && task.getInputRefs().containsKey(key)) {
            return task.getInputRefs().get(key);
        }
        if (task.getParameters() != null && task.getParameters().containsKey(key)) {
            return task.getParameters().get(key);
        }
        return null;
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private Path manifestFile(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("samples").resolve("manifests").resolve(sampleId + "_manifest.json");
    }

    private Path chunksDir(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("samples").resolve("chunks").resolve(sampleId);
    }

    private Path analysisDir(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("analysis").resolve("per_chunk").resolve(sampleId);
    }

    private Path legacyAnalysisDir(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("samples").resolve("analysis").resolve(sampleId);
    }

    private Path analysisSummary(String projectId, String sampleId) {
        return analysisDir(projectId, sampleId).resolve("summary.json");
    }

    private Path coverageReport(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("analysis").resolve("coverage").resolve(sampleId + "_coverage.json");
    }

    private Path bookReport(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("analysis").resolve("per_book").resolve(sampleId + "_report.md");
    }

    private Path legacyBookReport(String projectId, String sampleId) {
        return projectRoot(projectId).resolve("samples").resolve("reports").resolve(sampleId + "_report.md");
    }

    private Path crossBookReport(String projectId) {
        return projectRoot(projectId).resolve("analysis").resolve("cross_book").resolve("cross_book_synthesis.md");
    }

    private Path techniqueSummary(String projectId) {
        return projectRoot(projectId).resolve("analysis").resolve("cross_book").resolve("technique_summary.json");
    }

    private int countFiles(Path dir, String suffix) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(suffix)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Optional<Map<String, Object>> readOptionalJson(Path file) {
        return Files.exists(file) ? Optional.of(readJson(file)) : Optional.empty();
    }

    private Optional<String> readOptionalText(Path file) {
        return Files.exists(file) ? Optional.of(readText(file)) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    private List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private Map<String, Object> readJson(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取JSON文件失败: " + file.getFileName(), e);
        }
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取文本文件失败: " + file.getFileName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object camelize(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((key, childValue) -> result.put(toCamelCase(String.valueOf(key)), camelize(childValue)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::camelize).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> camelizeMap(Map<String, Object> map) {
        return (Map<String, Object>) camelize(map);
    }

    private String toCamelCase(String key) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (char ch : key.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            result.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return result.toString();
    }

    private String relativeIfExists(String projectId, Path path) {
        return Files.exists(path) ? relative(projectId, path) : null;
    }

    private String relative(String projectId, Path path) {
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

    private void validateId(String value, String fieldName) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("非法" + fieldName + ": " + value);
        }
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private String firstString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
