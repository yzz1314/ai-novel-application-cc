package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MemoryService {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ProjectService projectService;
    private final TaskExecutorService taskExecutorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    public Map<String, Object> getOverview(String projectId) {
        projectService.getProject(projectId);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("projectId", projectId);
        overview.put("types", memoryTypes().stream().map(type -> memoryTypeSummary(projectId, type)).toList());
        overview.put("snapshots", listSnapshots(projectId));
        overview.put("continuityReports", listContinuityReports(projectId));
        overview.put("auditReports", listAuditReports(projectId));
        overview.put("latestTasks", taskExecutorService.listTasksByProject(projectId).stream()
            .filter(task -> "memory_extraction".equals(task.getTaskType())
                || "memory_query".equals(task.getTaskType())
                || "continuity_check".equals(task.getTaskType())
                || "memory_audit".equals(task.getTaskType()))
            .sorted(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(10)
            .map(this::taskSummary)
            .toList());
        return overview;
    }

    public Map<String, Object> getMemoryType(String projectId, String type) {
        projectService.getProject(projectId);
        String normalizedType = normalizeType(type);
        Path markdown = memoryDir(projectId).resolve(normalizedType + ".md");
        Map<String, Object> response = memoryTypeSummary(projectId, normalizedType);
        response.put("content", Files.exists(markdown) ? readText(markdown) : "");
        response.put("json", readJsonList(jsonFile(projectId, normalizedType)));
        return response;
    }

    public List<Map<String, Object>> getCharacters(String projectId) {
        projectService.getProject(projectId);
        return readJsonList(memoryDir(projectId).resolve("characters.json"));
    }

    public List<Map<String, Object>> getWorldSettings(String projectId) {
        projectService.getProject(projectId);
        return readJsonList(memoryDir(projectId).resolve("world_settings.json"));
    }

    public List<Map<String, Object>> getTimeline(String projectId) {
        projectService.getProject(projectId);
        return readJsonList(memoryDir(projectId).resolve("timeline.json"));
    }

    public List<Map<String, Object>> listSnapshots(String projectId) {
        projectService.getProject(projectId);
        Path snapshotsDir = memoryDir(projectId).resolve("snapshots");
        if (!Files.exists(snapshotsDir)) {
            return List.of();
        }

        List<Map<String, Object>> snapshots = new ArrayList<>();
        try (var stream = Files.list(snapshotsDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    item.put("path", relative(projectId, path));
                    item.put("updatedAt", modifiedAt(path));
                    readOptionalSnapshot(path, item);
                    snapshots.add(item);
                });
        } catch (IOException e) {
            throw new RuntimeException("读取记忆快照失败", e);
        }

        snapshots.sort(Comparator.comparing(snapshot -> String.valueOf(snapshot.get("updatedAt")), Comparator.reverseOrder()));
        return snapshots;
    }

    public Map<String, Object> getSnapshot(String projectId, String snapshotId) {
        projectService.getProject(projectId);
        validateId(snapshotId, "snapshotId");
        Path file = memoryDir(projectId).resolve("snapshots").resolve(snapshotId + ".json");
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("记忆快照不存在: " + snapshotId);
        }
        Map<String, Object> response = readJsonMap(file);
        response.put("id", snapshotId);
        response.put("path", relative(projectId, file));
        response.put("updatedAt", modifiedAt(file));
        return response;
    }

    public List<Map<String, Object>> listContinuityReports(String projectId) {
        projectService.getProject(projectId);
        Path continuityDir = memoryDir(projectId).resolve("continuity");
        if (!Files.exists(continuityDir)) {
            return List.of();
        }

        List<Map<String, Object>> reports = new ArrayList<>();
        try (var stream = Files.list(continuityDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    item.put("path", relative(projectId, path));
                    item.put("updatedAt", modifiedAt(path));
                    try {
                        Map<String, Object> report = readJsonMap(path);
                        item.put("chapterId", report.get("chapter_id"));
                        item.put("bookId", report.get("book_id"));
                        item.put("chapterNumber", report.get("chapter_number"));
                        item.put("chapterTitle", report.get("chapter_title"));
                        item.put("hasIssues", report.get("has_issues"));
                        item.put("criticalCount", report.get("critical_count"));
                        item.put("majorCount", report.get("major_count"));
                        item.put("minorCount", report.get("minor_count"));
                        item.put("checkedAt", report.get("checked_at"));
                    } catch (Exception ignored) {
                        // Keep listing usable even if a report is partially written.
                    }
                    reports.add(item);
                });
        } catch (IOException e) {
            throw new RuntimeException("读取连续性检查报告失败", e);
        }

        reports.sort(Comparator.comparing(report -> String.valueOf(report.get("updatedAt")), Comparator.reverseOrder()));
        return reports;
    }

    public Map<String, Object> getContinuityReport(String projectId, String reportId) {
        projectService.getProject(projectId);
        validateId(reportId, "reportId");
        Path file = memoryDir(projectId).resolve("continuity").resolve(reportId + ".json");
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("连续性检查报告不存在: " + reportId);
        }
        Map<String, Object> response = readJsonMap(file);
        response.put("id", reportId);
        response.put("path", relative(projectId, file));
        response.put("updatedAt", modifiedAt(file));
        return response;
    }

    public List<Map<String, Object>> listAuditReports(String projectId) {
        projectService.getProject(projectId);
        Path auditDir = memoryDir(projectId).resolve("audits");
        if (!Files.exists(auditDir)) {
            return List.of();
        }

        List<Map<String, Object>> reports = new ArrayList<>();
        try (var stream = Files.list(auditDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    item.put("path", relative(projectId, path));
                    item.put("updatedAt", modifiedAt(path));
                    try {
                        Map<String, Object> report = readJsonMap(path);
                        item.put("bookId", report.get("book_id"));
                        item.put("hasIssues", report.get("has_issues"));
                        item.put("issueCount", report.get("issue_count"));
                        item.put("criticalCount", report.get("critical_count"));
                        item.put("majorCount", report.get("major_count"));
                        item.put("minorCount", report.get("minor_count"));
                        item.put("auditedAt", report.get("audited_at"));
                    } catch (Exception ignored) {
                        // Keep listing usable even if a report is partially written.
                    }
                    reports.add(item);
                });
        } catch (IOException e) {
            throw new RuntimeException("读取记忆审计报告失败", e);
        }

        reports.sort(Comparator.comparing(report -> String.valueOf(report.get("updatedAt")), Comparator.reverseOrder()));
        return reports;
    }

    public Map<String, Object> getAuditReport(String projectId, String reportId) {
        projectService.getProject(projectId);
        validateId(reportId, "reportId");
        Path file = memoryDir(projectId).resolve("audits").resolve(reportId + ".json");
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("记忆审计报告不存在: " + reportId);
        }
        Map<String, Object> response = readJsonMap(file);
        response.put("id", reportId);
        response.put("path", relative(projectId, file));
        response.put("updatedAt", modifiedAt(file));
        return response;
    }

    public Task auditMemory(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.put("project_id", projectId);
        parameters.putIfAbsent("book_id", "default");
        parameters.put("audit", true);

        Task task = taskExecutorService.createTask(
            projectId,
            "memory_audit",
            "memory_query",
            Map.of("book_id", parameters.get("book_id")),
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    public Task checkContinuity(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.put("project_id", projectId);
        parameters.putIfAbsent("book_id", "default");
        parameters.putIfAbsent("chapter_id", defaultChapterId(parameters));

        Task task = taskExecutorService.createTask(
            projectId,
            "continuity_check",
            "memory_query",
            Map.of(
                "book_id", parameters.get("book_id"),
                "chapter_id", parameters.get("chapter_id")
            ),
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    public Task rebuildMemory(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> parameters = new LinkedHashMap<>(request == null ? Map.of() : request);
        parameters.putIfAbsent("project_id", projectId);
        parameters.putIfAbsent("book_id", "default");

        Task task = taskExecutorService.createTask(
            projectId,
            "memory_extraction",
            "memory_extraction",
            Map.of(),
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    private String defaultChapterId(Map<String, Object> parameters) {
        Object chapterNumber = parameters.get("chapter_number");
        if (chapterNumber == null) {
            chapterNumber = parameters.get("chapterNumber");
        }
        return chapterNumber == null ? "chapter_1" : "chapter_" + chapterNumber;
    }

    private Map<String, Object> memoryTypeSummary(String projectId, String type) {
        Path markdown = memoryDir(projectId).resolve(type + ".md");
        Path json = jsonFile(projectId, type);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("title", titleFor(type));
        item.put("path", relative(projectId, markdown));
        item.put("exists", Files.exists(markdown));
        item.put("updatedAt", Files.exists(markdown) ? modifiedAt(markdown) : "");
        item.put("contentLength", Files.exists(markdown) ? readText(markdown).length() : 0);
        item.put("jsonPath", Files.exists(json) ? relative(projectId, json) : null);
        item.put("itemCount", readJsonList(json).size());
        return item;
    }

    private Path jsonFile(String projectId, String type) {
        return switch (type) {
            case "characters" -> memoryDir(projectId).resolve("characters.json");
            case "foreshadowing" -> memoryDir(projectId).resolve("suspenses.json");
            case "timeline" -> memoryDir(projectId).resolve("timeline.json");
            case "relationships" -> memoryDir(projectId).resolve("characters.json");
            case "cognition" -> memoryDir(projectId).resolve("characters.json");
            case "canon" -> memoryDir(projectId).resolve("world_settings.json");
            default -> memoryDir(projectId).resolve(type + ".json");
        };
    }

    private List<String> memoryTypes() {
        return List.of("characters", "foreshadowing", "timeline", "relationships", "cognition", "canon");
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase();
        if (!memoryTypes().contains(normalized)) {
            throw new IllegalArgumentException("不支持的记忆类型: " + type);
        }
        return normalized;
    }

    private String titleFor(String type) {
        return switch (type) {
            case "characters" -> "人物状态记忆";
            case "foreshadowing" -> "伏笔记忆";
            case "timeline" -> "时间线记忆";
            case "relationships" -> "人物关系记忆";
            case "cognition" -> "角色认知记忆";
            case "canon" -> "Canon正史规则";
            default -> type;
        };
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
        summary.put("finishedAt", task.getFinishedAt());
        return summary;
    }

    private void readOptionalSnapshot(Path path, Map<String, Object> item) {
        try {
            Map<String, Object> snapshot = readJsonMap(path);
            item.put("chapterId", snapshot.get("chapter_id"));
            item.put("bookId", snapshot.get("book_id"));
            item.put("chapterTitle", snapshot.get("chapter_title"));
            item.put("chapterNumber", snapshot.get("chapter_number"));
            item.put("extractedAt", snapshot.get("extracted_at"));
        } catch (Exception ignored) {
            // Summary still works without parsing details.
        }
    }

    private List<Map<String, Object>> readJsonList(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取记忆JSON失败: " + file.getFileName(), e);
        }
    }

    private Map<String, Object> readJsonMap(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取记忆快照失败: " + file.getFileName(), e);
        }
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取记忆文件失败: " + file.getFileName(), e);
        }
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private Path memoryDir(String projectId) {
        return projectRoot(projectId).resolve("memory");
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
}
