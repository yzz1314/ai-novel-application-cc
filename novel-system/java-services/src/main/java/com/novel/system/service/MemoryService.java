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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MemoryService {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final DateTimeFormatter VERSION_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final List<String> VERSIONED_MEMORY_FILES = List.of(
        "characters.md",
        "foreshadowing.md",
        "timeline.md",
        "relationships.md",
        "cognition.md",
        "canon.md",
        "characters.json",
        "world_settings.json",
        "plots.json",
        "suspenses.json",
        "timeline.json"
    );

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
        overview.put("versions", listVersions(projectId));
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

    public List<Map<String, Object>> listVersions(String projectId) {
        projectService.getProject(projectId);
        Path versionsDir = memoryVersionsDir(projectId);
        if (!Files.exists(versionsDir)) {
            return List.of();
        }

        List<Map<String, Object>> versions = new ArrayList<>();
        try (var stream = Files.list(versionsDir)) {
            stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        Map<String, Object> version = readJsonMap(path);
                        if ("memory_snapshot".equals(version.get("version_type"))) {
                            versions.add(versionSummary(projectId, path, version));
                        }
                    } catch (Exception ignored) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                        item.put("path", relative(projectId, path));
                        item.put("updatedAt", modifiedAt(path));
                        versions.add(item);
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("读取记忆版本失败", e);
        }

        versions.sort(Comparator.comparing(version -> String.valueOf(version.get("createdAt")), Comparator.reverseOrder()));
        return versions;
    }

    public Map<String, Object> getVersion(String projectId, String versionId) {
        projectService.getProject(projectId);
        validateId(versionId, "versionId");
        Path file = memoryVersionsDir(projectId).resolve(versionId + ".json");
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("记忆版本不存在: " + versionId);
        }
        Map<String, Object> version = readJsonMap(file);
        version.put("id", versionId);
        version.put("path", relative(projectId, file));
        version.put("updatedAt", modifiedAt(file));
        return version;
    }

    public Map<String, Object> createVersion(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        return createMemoryVersion(projectId, request == null ? Map.of() : request);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> restoreVersion(String projectId, String versionId, Map<String, Object> request) {
        projectService.getProject(projectId);
        Map<String, Object> version = getVersion(projectId, versionId);
        Object rawFiles = version.get("files");
        if (!(rawFiles instanceof List<?> rawList)) {
            throw new IllegalArgumentException("记忆版本文件清单为空: " + versionId);
        }

        Map<String, Object> previousVersion = createMemoryVersion(
            projectId,
            Map.of(
                "reason", "before_memory_restore",
                "actor", stringValue(requestValue(request, "actor"), "system"),
                "note", "Before restoring memory version " + versionId
            )
        );

        Path memoryDir = memoryDir(projectId);
        try {
            Files.createDirectories(memoryDir);
            Set<String> restoredNames = new HashSet<>();
            for (Object rawFile : rawList) {
                if (!(rawFile instanceof Map<?, ?> map)) {
                    continue;
                }
                String name = stringValue(map.get("name"), "");
                if (!VERSIONED_MEMORY_FILES.contains(name)) {
                    continue;
                }
                String content = stringValue(map.get("content"), "");
                Files.writeString(memoryDir.resolve(name), content, StandardCharsets.UTF_8);
                restoredNames.add(name);
            }

            if (Boolean.TRUE.equals(requestValue(request, "deleteMissing"))) {
                for (String name : VERSIONED_MEMORY_FILES) {
                    if (!restoredNames.contains(name)) {
                        Files.deleteIfExists(memoryDir.resolve(name));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("恢复记忆版本失败: " + versionId, e);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("project_id", projectId);
        event.put("event_type", "memory_restore");
        event.put("restored_from_version_id", versionId);
        event.put("previous_version_id", previousVersion.get("id"));
        event.put("actor", stringValue(requestValue(request, "actor"), "system"));
        event.put("note", stringValue(requestValue(request, "note"), ""));
        event.put("restored_at", LocalDateTime.now().toString());
        Path eventFile = memoryVersionsDir(projectId).resolve("memory_restore_" + LocalDateTime.now().format(VERSION_TIMESTAMP) + ".json");
        writeJson(eventFile, event);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "restored");
        response.put("projectId", projectId);
        response.put("restoredFromVersionId", versionId);
        response.put("previousVersionId", previousVersion.get("id"));
        response.put("previousVersionPath", previousVersion.get("path"));
        response.put("restoreEventPath", relative(projectId, eventFile));
        response.put("restoredFileCount", rawList.size());
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
                        Map<String, Object> summary = resolutionSummary(report);
                        item.put("resolvedCount", summary.get("resolved_count"));
                        item.put("acceptedRiskCount", summary.get("accepted_risk_count"));
                        item.put("ignoredCount", summary.get("ignored_count"));
                        item.put("openCount", summary.get("open_count"));
                        item.put("allIssuesHandled", summary.get("all_issues_handled"));
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
        normalizeIssues(response);
        Map<String, Object> summary = resolutionSummary(response);
        response.put("resolution_summary", summary);
        response.put("resolutionSummary", summary);
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
                        Map<String, Object> summary = resolutionSummary(report);
                        item.put("resolvedCount", summary.get("resolved_count"));
                        item.put("acceptedRiskCount", summary.get("accepted_risk_count"));
                        item.put("ignoredCount", summary.get("ignored_count"));
                        item.put("openCount", summary.get("open_count"));
                        item.put("allIssuesHandled", summary.get("all_issues_handled"));
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
        normalizeIssues(response);
        Map<String, Object> summary = resolutionSummary(response);
        response.put("resolution_summary", summary);
        response.put("resolutionSummary", summary);
        response.put("id", reportId);
        response.put("path", relative(projectId, file));
        response.put("updatedAt", modifiedAt(file));
        return response;
    }

    public Map<String, Object> resolveContinuityIssue(
            String projectId,
            String reportId,
            Integer issueIndex,
            Map<String, Object> request) {
        return resolveReportIssue(projectId, "continuity", reportId, issueIndex, request);
    }

    public Map<String, Object> resolveAuditIssue(
            String projectId,
            String reportId,
            Integer issueIndex,
            Map<String, Object> request) {
        return resolveReportIssue(projectId, "audit", reportId, issueIndex, request);
    }

    public Map<String, Object> applyAuditIssueFix(
            String projectId,
            String reportId,
            Integer issueIndex,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        validateId(reportId, "reportId");
        if (issueIndex == null || issueIndex < 0) {
            throw new IllegalArgumentException("非法issueIndex: " + issueIndex);
        }

        Path reportPath = reportFile(projectId, "audit", reportId);
        if (!Files.exists(reportPath)) {
            throw new ResourceNotFoundException("记忆审计报告不存在: " + reportId);
        }

        Map<String, Object> report = readJsonMap(reportPath);
        List<Map<String, Object>> issues = normalizeIssues(report);
        if (issueIndex >= issues.size()) {
            throw new IllegalArgumentException("issueIndex超出范围: " + issueIndex);
        }

        Map<String, Object> issue = issues.get(issueIndex);
        Map<String, Object> fix = asMap(issue.get("fix"));
        if (fix.isEmpty()) {
            throw new IllegalArgumentException("该记忆审计问题没有可自动应用的修复");
        }

        Map<String, Object> beforeVersion = createMemoryVersion(projectId, Map.of(
            "reason", "before_memory_audit_fix",
            "actor", stringValue(requestValue(request, "actor"), "system"),
            "note", "Before applying memory audit fix " + reportId + "#" + issueIndex
        ));

        Map<String, Object> fixResult = applyMemoryFix(projectId, fix);
        String now = LocalDateTime.now().toString();
        String actor = stringValue(firstPresent(
            requestValue(request, "actor"),
            requestValue(request, "reviewer"),
            requestValue(request, "user")
        ), "human");

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("status", "resolved");
        resolution.put("action", "applied_fix");
        resolution.put("note", stringValue(requestValue(request, "note"), ""));
        resolution.put("actor", actor);
        resolution.put("updated_at", now);
        resolution.put("updatedAt", now);
        resolution.put("resolved_at", now);
        resolution.put("fix", fix);
        resolution.put("fix_result", fixResult);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = issue.get("resolution_history") instanceof List<?> rawHistory
            ? new ArrayList<>((List<Map<String, Object>>) rawHistory)
            : new ArrayList<>();
        Map<String, Object> historyItem = new LinkedHashMap<>(resolution);
        historyItem.put("issue_index", issueIndex);
        historyItem.put("issue_id", issue.get("issue_id"));
        history.add(historyItem);

        issue.put("resolution", resolution);
        issue.put("resolution_status", "resolved");
        issue.put("resolutionStatus", "resolved");
        issue.put("resolution_history", history);
        issue.put("resolved", true);
        issue.put("fix_applied", true);
        issue.put("fixApplied", true);
        issue.put("fix_result", fixResult);
        issue.put("fixResult", fixResult);
        issue.put("fix_applied_at", now);
        issue.put("fixAppliedAt", now);

        Map<String, Object> summary = resolutionSummary(report);
        report.put("resolution_summary", summary);
        report.put("resolutionSummary", summary);
        report.put("resolution_updated_at", now);
        report.put("resolutionUpdatedAt", now);
        writeJson(reportPath, report);

        Path eventFile = saveMemoryFixEvent(
            projectId,
            reportId,
            issueIndex,
            issue,
            fix,
            fixResult,
            beforeVersion,
            actor,
            stringValue(requestValue(request, "note"), "")
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("reportId", reportId);
        response.put("issueIndex", issueIndex);
        response.put("issue", issue);
        response.put("fix", fix);
        response.put("fixResult", fixResult);
        response.put("resolution", resolution);
        response.put("resolutionSummary", summary);
        response.put("beforeVersionId", beforeVersion.get("id"));
        response.put("beforeVersionPath", beforeVersion.get("path"));
        response.put("eventPath", relative(projectId, eventFile));
        response.put("reportPath", relative(projectId, reportPath));
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
        createMemoryVersion(projectId, Map.of(
            "reason", "before_memory_rebuild",
            "actor", stringValue(requestValue(request, "actor"), "system"),
            "note", stringValue(requestValue(request, "note"), "Automatic snapshot before memory rebuild")
        ));

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

    private Map<String, Object> createMemoryVersion(String projectId, Map<String, Object> request) {
        Path versionsDir = memoryVersionsDir(projectId);
        try {
            Files.createDirectories(versionsDir);
        } catch (IOException e) {
            throw new RuntimeException("创建记忆版本目录失败", e);
        }

        String reason = stringValue(requestValue(request, "reason"), "manual_snapshot");
        String timestamp = LocalDateTime.now().format(VERSION_TIMESTAMP);
        String versionId = "memory_" + sanitizeFilePart(reason) + "_" + timestamp;
        Path versionFile = versionsDir.resolve(versionId + ".json");
        List<Map<String, Object>> files = new ArrayList<>();
        int markdownCount = 0;
        int jsonCount = 0;
        long totalSizeBytes = 0;

        for (String name : VERSIONED_MEMORY_FILES) {
            Path file = memoryDir(projectId).resolve(name);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                continue;
            }
            String content = readText(file);
            long sizeBytes = size(file);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("path", relative(projectId, file));
            item.put("updatedAt", modifiedAt(file));
            item.put("sizeBytes", sizeBytes);
            item.put("contentLength", content.length());
            item.put("content", content);
            files.add(item);
            totalSizeBytes += sizeBytes;
            if (name.endsWith(".md")) {
                markdownCount++;
            } else if (name.endsWith(".json")) {
                jsonCount++;
            }
        }

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", versionId);
        version.put("project_id", projectId);
        version.put("version_type", "memory_snapshot");
        version.put("reason", reason);
        version.put("actor", stringValue(requestValue(request, "actor"), "system"));
        version.put("note", stringValue(requestValue(request, "note"), ""));
        version.put("created_at", LocalDateTime.now().toString());
        version.put("file_count", files.size());
        version.put("markdown_count", markdownCount);
        version.put("json_count", jsonCount);
        version.put("total_size_bytes", totalSizeBytes);
        version.put("files", files);
        writeJson(versionFile, version);

        Map<String, Object> response = versionSummary(projectId, versionFile, version);
        response.put("files", files);
        return response;
    }

    private Map<String, Object> versionSummary(String projectId, Path versionFile, Map<String, Object> version) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", version.getOrDefault("id", stripSuffix(versionFile.getFileName().toString(), ".json")));
        item.put("reason", version.get("reason"));
        item.put("actor", version.get("actor"));
        item.put("note", version.get("note"));
        item.put("createdAt", firstPresent(version.get("created_at"), version.get("createdAt")));
        item.put("fileCount", version.get("file_count"));
        item.put("markdownCount", version.get("markdown_count"));
        item.put("jsonCount", version.get("json_count"));
        item.put("totalSizeBytes", version.get("total_size_bytes"));
        item.put("path", relative(projectId, versionFile));
        item.put("updatedAt", modifiedAt(versionFile));
        return item;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveReportIssue(
            String projectId,
            String reportType,
            String reportId,
            Integer issueIndex,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        validateId(reportId, "reportId");
        if (issueIndex == null || issueIndex < 0) {
            throw new IllegalArgumentException("非法issueIndex: " + issueIndex);
        }

        Path file = reportFile(projectId, reportType, reportId);
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException(reportDisplayName(reportType) + "不存在: " + reportId);
        }

        Map<String, Object> report = readJsonMap(file);
        List<Map<String, Object>> issues = normalizeIssues(report);
        if (issueIndex >= issues.size()) {
            throw new IllegalArgumentException("issueIndex超出范围: " + issueIndex);
        }

        Map<String, Object> issue = issues.get(issueIndex);
        Map<String, Object> previousResolution = asMap(issue.get("resolution"));
        String status = normalizeResolutionStatus(stringValue(
            firstPresent(requestValue(request, "status"), requestValue(request, "resolutionStatus")),
            "resolved"
        ));
        String actor = stringValue(firstPresent(
            requestValue(request, "actor"),
            requestValue(request, "reviewer"),
            requestValue(request, "user")
        ), "human");
        String note = stringValue(firstPresent(
            requestValue(request, "note"),
            requestValue(request, "decisionNote"),
            requestValue(request, "resolution")
        ), "");
        String action = stringValue(firstPresent(requestValue(request, "action"), requestValue(request, "decision")), status);
        String now = LocalDateTime.now().toString();

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("status", status);
        resolution.put("action", action);
        resolution.put("note", note);
        resolution.put("actor", actor);
        resolution.put("updated_at", now);
        resolution.put("updatedAt", now);
        if ("open".equals(status)) {
            resolution.put("reopened_at", now);
        } else {
            resolution.put("resolved_at", now);
        }

        List<Map<String, Object>> history = issue.get("resolution_history") instanceof List<?> rawHistory
            ? new ArrayList<>((List<Map<String, Object>>) rawHistory)
            : new ArrayList<>();
        Map<String, Object> historyItem = new LinkedHashMap<>(resolution);
        historyItem.put("previous_status", previousResolution.get("status"));
        historyItem.put("issue_index", issueIndex);
        historyItem.put("issue_id", issue.get("issue_id"));
        history.add(historyItem);

        issue.put("resolution", resolution);
        issue.put("resolution_status", status);
        issue.put("resolutionStatus", status);
        issue.put("resolution_history", history);
        issue.put("resolved", !"open".equals(status));

        Map<String, Object> summary = resolutionSummary(report);
        report.put("resolution_summary", summary);
        report.put("resolutionSummary", summary);
        report.put("resolution_updated_at", now);
        report.put("resolutionUpdatedAt", now);

        writeJson(file, report);
        Path decisionFile = saveResolutionDecision(
            projectId,
            reportType,
            reportId,
            issueIndex,
            issue,
            previousResolution,
            resolution
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("reportType", reportType);
        response.put("reportId", reportId);
        response.put("issueIndex", issueIndex);
        response.put("issue", issue);
        response.put("resolution", resolution);
        response.put("resolutionSummary", summary);
        response.put("decisionPath", relative(projectId, decisionFile));
        response.put("reportPath", relative(projectId, file));
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeIssues(Map<String, Object> report) {
        Object rawIssues = report.get("issues");
        if (!(rawIssues instanceof List<?> rawList)) {
            report.put("issues", new ArrayList<Map<String, Object>>());
            return new ArrayList<>();
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int index = 0; index < rawList.size(); index++) {
            Object rawIssue = rawList.get(index);
            Map<String, Object> issue;
            if (rawIssue instanceof Map<?, ?> map) {
                issue = new LinkedHashMap<>();
                map.forEach((key, value) -> issue.put(String.valueOf(key), value));
            } else {
                issue = new LinkedHashMap<>();
                issue.put("title", String.valueOf(rawIssue));
                issue.put("description", String.valueOf(rawIssue));
            }
            if (!issue.containsKey("issue_id") || stringValue(issue.get("issue_id"), "").isBlank()) {
                issue.put("issue_id", stableIssueId(issue, index));
            }
            if (!issue.containsKey("resolution_status")) {
                Map<String, Object> resolution = asMap(issue.get("resolution"));
                String status = stringValue(resolution.get("status"), "open");
                issue.put("resolution_status", status);
                issue.put("resolutionStatus", status);
                issue.put("resolved", !"open".equals(status));
            }
            normalized.add(issue);
        }
        report.put("issues", normalized);
        return normalized;
    }

    private Map<String, Object> resolutionSummary(Map<String, Object> report) {
        List<Map<String, Object>> issues = normalizeIssues(report);
        int resolvedCount = 0;
        int acceptedRiskCount = 0;
        int ignoredCount = 0;
        int openCount = 0;
        for (Map<String, Object> issue : issues) {
            String status = normalizeResolutionStatus(stringValue(firstPresent(
                issue.get("resolution_status"),
                issue.get("resolutionStatus"),
                asMap(issue.get("resolution")).get("status")
            ), "open"));
            if ("resolved".equals(status)) {
                resolvedCount++;
            } else if ("accepted_risk".equals(status)) {
                acceptedRiskCount++;
            } else if ("ignored".equals(status)) {
                ignoredCount++;
            } else {
                openCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_issues", issues.size());
        summary.put("resolved_count", resolvedCount);
        summary.put("accepted_risk_count", acceptedRiskCount);
        summary.put("ignored_count", ignoredCount);
        summary.put("open_count", openCount);
        summary.put("handled_count", resolvedCount + acceptedRiskCount + ignoredCount);
        summary.put("all_issues_handled", !issues.isEmpty() && openCount == 0);
        return summary;
    }

    private Path saveResolutionDecision(
            String projectId,
            String reportType,
            String reportId,
            Integer issueIndex,
            Map<String, Object> issue,
            Map<String, Object> previousResolution,
            Map<String, Object> resolution) {
        Path dir = memoryDir(projectId).resolve("resolutions");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("创建记忆处理记录目录失败", e);
        }

        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String issueId = sanitizeFilePart(stringValue(issue.get("issue_id"), "issue_" + issueIndex));
        Path file = dir.resolve(reportType + "_" + sanitizeFilePart(reportId) + "_" + issueId + "_" + timestamp + ".json");
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("project_id", projectId);
        decision.put("report_type", reportType);
        decision.put("report_id", reportId);
        decision.put("issue_index", issueIndex);
        decision.put("issue_id", issue.get("issue_id"));
        decision.put("issue_title", issue.get("title"));
        decision.put("issue_severity", issue.get("severity"));
        decision.put("previous_resolution", previousResolution);
        decision.put("resolution", resolution);
        decision.put("created_at", LocalDateTime.now().toString());
        writeJson(file, decision);
        return file;
    }

    private Map<String, Object> applyMemoryFix(String projectId, Map<String, Object> fix) {
        String action = stringValue(fix.get("action"), "");
        String memoryFile = stringValue(fix.get("memory_file"), "");
        if (!VERSIONED_MEMORY_FILES.contains(memoryFile) || !memoryFile.endsWith(".json")) {
            throw new IllegalArgumentException("不支持自动修复的记忆文件: " + memoryFile);
        }

        Path file = memoryDir(projectId).resolve(memoryFile);
        List<Map<String, Object>> records = new ArrayList<>(readJsonList(file));
        if (records.isEmpty() && !Files.exists(file)) {
            throw new ResourceNotFoundException("记忆文件不存在: " + memoryFile);
        }

        return switch (action) {
            case "set_field" -> applySetFieldFix(file, records, fix);
            case "sort_by_chapter" -> applySortByChapterFix(file, records, fix);
            default -> throw new IllegalArgumentException("不支持的记忆自动修复动作: " + action);
        };
    }

    private Map<String, Object> applySetFieldFix(Path file, List<Map<String, Object>> records, Map<String, Object> fix) {
        Map<String, Object> match = asMap(fix.get("match"));
        String field = stringValue(fix.get("field"), "");
        if (field.isBlank()) {
            throw new IllegalArgumentException("记忆自动修复缺少field");
        }

        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> record : records) {
            if (!matchesMemoryRecord(record, match)) {
                continue;
            }
            Object previous = record.get(field);
            Object next = fix.get("value");
            if (!valuesEqual(previous, next)) {
                record.put(field, next);
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("field", field);
                change.put("previous", previous);
                change.put("next", next);
                change.put("match", match);
                changed.add(change);
            }
        }

        if (changed.isEmpty()) {
            throw new IllegalArgumentException("未找到需要更新的记忆条目");
        }

        writeJsonList(file, records);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "set_field");
        result.put("memoryFile", file.getFileName().toString());
        result.put("changedCount", changed.size());
        result.put("changes", changed);
        return result;
    }

    private Map<String, Object> applySortByChapterFix(Path file, List<Map<String, Object>> records, Map<String, Object> fix) {
        String field = stringValue(fix.get("field"), "chapter");
        List<Map<String, Object>> before = new ArrayList<>(records);
        records.sort(Comparator
            .comparing((Map<String, Object> item) -> numericSortValue(item.get(field)))
            .thenComparing(item -> stringValue(firstPresent(item.get("event_id"), item.get("title")), "")));
        if (before.equals(records)) {
            throw new IllegalArgumentException("记忆条目已经按章节排序，无需修复");
        }

        writeJsonList(file, records);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "sort_by_chapter");
        result.put("memoryFile", file.getFileName().toString());
        result.put("field", field);
        result.put("changedCount", records.size());
        result.put("firstChapter", records.isEmpty() ? null : records.get(0).get(field));
        result.put("lastChapter", records.isEmpty() ? null : records.get(records.size() - 1).get(field));
        return result;
    }

    private boolean matchesMemoryRecord(Map<String, Object> record, Map<String, Object> match) {
        if (match.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : match.entrySet()) {
            if (!valuesEqual(record.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private int numericSortValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number || right instanceof Number) {
            try {
                return Double.compare(Double.parseDouble(String.valueOf(left)), Double.parseDouble(String.valueOf(right))) == 0;
            } catch (NumberFormatException ignored) {
                // Fall back to string comparison.
            }
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private Path saveMemoryFixEvent(
            String projectId,
            String reportId,
            Integer issueIndex,
            Map<String, Object> issue,
            Map<String, Object> fix,
            Map<String, Object> fixResult,
            Map<String, Object> beforeVersion,
            String actor,
            String note) {
        Path dir = memoryDir(projectId).resolve("resolutions");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("创建记忆修复记录目录失败", e);
        }

        String timestamp = LocalDateTime.now().format(VERSION_TIMESTAMP);
        String issueId = sanitizeFilePart(stringValue(issue.get("issue_id"), "issue_" + issueIndex));
        Path file = dir.resolve("audit_fix_" + sanitizeFilePart(reportId) + "_" + issueId + "_" + timestamp + ".json");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("project_id", projectId);
        event.put("event_type", "memory_audit_fix");
        event.put("report_id", reportId);
        event.put("issue_index", issueIndex);
        event.put("issue_id", issue.get("issue_id"));
        event.put("issue_title", issue.get("title"));
        event.put("actor", actor);
        event.put("note", note);
        event.put("before_version_id", beforeVersion.get("id"));
        event.put("before_version_path", beforeVersion.get("path"));
        event.put("fix", fix);
        event.put("fix_result", fixResult);
        event.put("created_at", LocalDateTime.now().toString());
        writeJson(file, event);
        return file;
    }

    private Path reportFile(String projectId, String reportType, String reportId) {
        return switch (reportType) {
            case "continuity" -> memoryDir(projectId).resolve("continuity").resolve(reportId + ".json");
            case "audit" -> memoryDir(projectId).resolve("audits").resolve(reportId + ".json");
            default -> throw new IllegalArgumentException("不支持的报告类型: " + reportType);
        };
    }

    private String reportDisplayName(String reportType) {
        return "continuity".equals(reportType) ? "连续性检查报告" : "记忆审计报告";
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

    private Path memoryVersionsDir(String projectId) {
        return memoryDir(projectId).resolve("versions");
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
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

    private void writeJson(Path file, Map<String, Object> value) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new RuntimeException("写入记忆报告失败: " + file.getFileName(), e);
        }
    }

    private void writeJsonList(Path file, List<Map<String, Object>> value) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new RuntimeException("写入记忆JSON失败: " + file.getFileName(), e);
        }
    }

    private Object requestValue(Map<String, Object> request, String key) {
        return request == null ? null : request.get(key);
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            return value;
        }
        return null;
    }

    private Map<String, Object> asMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private String normalizeResolutionStatus(String status) {
        String normalized = status == null ? "open" : status.trim().toLowerCase().replace("-", "_");
        return switch (normalized) {
            case "resolved", "fixed", "done", "closed", "已解决", "完成" -> "resolved";
            case "accepted_risk", "accept_risk", "risk_accepted", "接受风险" -> "accepted_risk";
            case "ignored", "ignore", "false_positive", "忽略" -> "ignored";
            case "open", "reopened", "pending", "待处理", "重开" -> "open";
            default -> throw new IllegalArgumentException("不支持的问题处理状态: " + status);
        };
    }

    private String stableIssueId(Map<String, Object> issue, int index) {
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("index", index);
        signature.put("issue_type", issue.get("issue_type"));
        signature.put("severity", issue.get("severity"));
        signature.put("title", issue.get("title"));
        signature.put("description", issue.get("description"));
        signature.put("conflict_chapters", issue.get("conflict_chapters"));
        try {
            String raw = objectMapper.writeValueAsString(signature);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 6); i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return "issue_" + hex;
        } catch (IOException | NoSuchAlgorithmException e) {
            return "issue_" + index;
        }
    }

    private String sanitizeFilePart(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.isBlank() ? "item" : sanitized;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }
}
