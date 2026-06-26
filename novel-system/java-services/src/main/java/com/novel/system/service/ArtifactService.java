package com.novel.system.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ArtifactService {

    private static final long PREVIEW_TEXT_LIMIT = 200_000L;
    private static final long SENSITIVE_PREVIEW_TEXT_LIMIT = 2_000L;
    private static final long LIST_FILE_SIZE_LIMIT = 50L * 1024L * 1024L;
    private static final int DIFF_LINE_LIMIT = 1_200;
    private static final int MAX_BULK_EXPORT_FILES = 200;
    private static final long MAX_BULK_EXPORT_BYTES = 200L * 1024L * 1024L;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "txt", "md", "json", "yaml", "yml", "csv", "log", "html", "xml"
    );

    private final ProjectService projectService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    public Map<String, Object> getOverview(String projectId) {
        projectService.getProject(projectId);
        Path root = projectRoot(projectId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("root", root.toString());
        response.put("categories", categoryDefinitions().stream().map(category -> categorySummary(projectId, category)).toList());
        response.put("recentArtifacts", listArtifacts(projectId, null, null, 20).get("items"));
        response.put("recentAuditEvents", listAuditEvents(projectId, 10).get("items"));
        return response;
    }

    public Map<String, Object> listArtifacts(String projectId, String category, String query, int limit) {
        projectService.getProject(projectId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 1000));
        List<Path> searchRoots = rootsForCategory(projectId, category);
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Map<String, Object>> items = new ArrayList<>();

        for (Path root : searchRoots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> fileSize(path) <= LIST_FILE_SIZE_LIMIT)
                    .filter(path -> normalizedQuery.isBlank() || relative(projectId, path).toLowerCase(Locale.ROOT).contains(normalizedQuery))
                    .map(path -> artifactSummary(projectId, path))
                    .forEach(items::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan artifacts: " + root.getFileName(), e);
            }
        }

        items.sort(Comparator.comparing(item -> String.valueOf(item.get("updatedAt")), Comparator.reverseOrder()));
        if (items.size() > safeLimit) {
            items = new ArrayList<>(items.subList(0, safeLimit));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("category", category == null || category.isBlank() ? "all" : category);
        response.put("query", query);
        response.put("limit", safeLimit);
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> getArtifact(String projectId, String pathValue) {
        return getArtifact(projectId, pathValue, false);
    }

    public Map<String, Object> getArtifact(String projectId, String pathValue, boolean allowSensitive) {
        return getArtifact(projectId, pathValue, allowSensitive, "human", "");
    }

    public Map<String, Object> getArtifact(
            String projectId,
            String pathValue,
            boolean allowSensitive,
            String actor,
            String reason) {
        projectService.getProject(projectId);
        Path file = resolveProjectPath(projectId, pathValue);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("Artifact does not exist: " + pathValue);
        }

        Map<String, Object> response = artifactSummary(projectId, file);
        boolean sensitive = isSensitiveArtifact(projectId, file);
        response.put("sensitive", sensitive);
        response.put("sensitivePolicy", sensitive ? "sample_source_protected" : "none");
        if (sensitive && allowSensitive) {
            appendSensitiveAccessAudit(projectId, "sensitive_preview", actor, reason, Map.of(
                "path", relative(projectId, file)
            ));
        }
        appendArtifactAccessAudit(projectId, "preview", actor, reason, file, sensitive, Map.of(
            "allowSensitive", allowSensitive,
            "redacted", sensitive && !allowSensitive
        ));
        if (isTextArtifact(file)) {
            String content = readText(file);
            long limit = sensitive && !allowSensitive ? SENSITIVE_PREVIEW_TEXT_LIMIT : PREVIEW_TEXT_LIMIT;
            response.put("content", content.length() > limit ? content.substring(0, (int) limit) : content);
            response.put("truncated", content.length() > limit);
            response.put("redacted", sensitive && !allowSensitive);
            if (sensitive && !allowSensitive) {
                response.put("notice", "样本原文或其切片已启用敏感保护，仅展示短预览；如需完整内容请使用显式授权参数。");
            }
            if (extension(file).equals("json")) {
                response.put("json", readJsonOrNull(file));
            }
        } else {
            response.put("content", null);
            response.put("truncated", false);
            response.put("redacted", false);
        }
        return response;
    }

    public DownloadedArtifact downloadArtifact(String projectId, String pathValue) {
        return downloadArtifact(projectId, pathValue, false);
    }

    public DownloadedArtifact downloadArtifact(String projectId, String pathValue, boolean allowSensitive) {
        return downloadArtifact(projectId, pathValue, allowSensitive, "human", "");
    }

    public DownloadedArtifact downloadArtifact(
            String projectId,
            String pathValue,
            boolean allowSensitive,
            String actor,
            String reason) {
        projectService.getProject(projectId);
        Path file = resolveProjectPath(projectId, pathValue);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("Artifact does not exist: " + pathValue);
        }
        boolean sensitive = isSensitiveArtifact(projectId, file);
        if (sensitive && !allowSensitive) {
            appendArtifactAccessAudit(projectId, "download_denied", actor, reason, file, true, Map.of(
                "policy", "sample_source_protected"
            ));
            throw new IllegalArgumentException("敏感样本原文默认禁止直接下载，请显式授权后重试: " + pathValue);
        }
        if (sensitive && allowSensitive) {
            appendSensitiveAccessAudit(projectId, "sensitive_download", actor, reason, Map.of(
                "path", relative(projectId, file)
            ));
        }
        appendArtifactAccessAudit(projectId, "download", actor, reason, file, sensitive, Map.of(
            "allowSensitive", allowSensitive
        ));
        try {
            return new DownloadedArtifact(
                file.getFileName().toString(),
                mediaType(file),
                new ByteArrayResource(Files.readAllBytes(file))
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to read artifact: " + file.getFileName(), e);
        }
    }

    public DownloadedArtifact bulkDownloadArtifacts(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        List<String> paths = stringList(request == null ? null : request.get("paths"));
        boolean allowSensitive = booleanValue(request == null ? null : request.get("allowSensitive"), false);
        String actor = stringValue(request == null ? null : request.get("actor"), "human");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("paths 不能为空");
        }
        if (paths.size() > MAX_BULK_EXPORT_FILES) {
            throw new IllegalArgumentException("单次批量导出最多支持 " + MAX_BULK_EXPORT_FILES + " 个文件");
        }

        List<Map<String, Object>> included = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        long totalBytes = 0L;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                for (String pathValue : paths) {
                    Path file = resolveProjectPath(projectId, pathValue);
                    if (!Files.isRegularFile(file)) {
                        skipped.add(skip(pathValue, "not_found"));
                        continue;
                    }
                    if (isSensitiveArtifact(projectId, file) && !allowSensitive) {
                        skipped.add(skip(pathValue, "sensitive_sample_protected"));
                        continue;
                    }
                    long fileSize = Files.size(file);
                    if (totalBytes + fileSize > MAX_BULK_EXPORT_BYTES) {
                        skipped.add(skip(pathValue, "bulk_export_size_limit"));
                        continue;
                    }
                    totalBytes += fileSize;
                    String relative = relative(projectId, file);
                    zip.putNextEntry(new ZipEntry(relative));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    included.add(artifactSummary(projectId, file));
                }
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("projectId", projectId);
                manifest.put("exportedAt", LocalDateTime.now().toString());
                manifest.put("allowSensitive", allowSensitive);
                manifest.put("included", included);
                manifest.put("skipped", skipped);
                zip.putNextEntry(new ZipEntry("artifact_export_manifest.json"));
                zip.write(toJson(manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            Map<String, Object> audit = auditBase(projectId, "bulk_export", actor, stringValue(request == null ? null : request.get("reason"), ""));
            audit.put("includedCount", included.size());
            audit.put("skippedCount", skipped.size());
            audit.put("paths", paths);
            appendAudit(projectId, audit);
            String filename = "artifacts_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".zip";
            return new DownloadedArtifact(filename, MediaType.APPLICATION_OCTET_STREAM, new ByteArrayResource(buffer.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException("批量导出产物失败", e);
        }
    }

    public Map<String, Object> archiveArtifact(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        String pathValue = stringValue(request == null ? null : request.get("path"), null);
        String actor = stringValue(request == null ? null : request.get("actor"), "human");
        String reason = stringValue(request == null ? null : request.get("reason"), "");
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path source = resolveProjectPath(projectId, pathValue);
        if (!Files.isRegularFile(source)) {
            throw new ResourceNotFoundException("Artifact does not exist: " + pathValue);
        }
        String relative = relative(projectId, source);
        if (isGovernanceArtifact(relative)) {
            throw new IllegalArgumentException("治理审计与归档目录不能再次归档: " + relative);
        }

        String archiveId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
            + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Path target = projectRoot(projectId)
            .resolve("artifacts")
            .resolve("archive")
            .resolve(archiveId)
            .resolve(relative)
            .normalize();
        if (!target.startsWith(projectRoot(projectId))) {
            throw new IllegalArgumentException("Archive target escapes project workspace");
        }

        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> audit = auditBase(projectId, "archive", actor, reason);
            audit.put("sourcePath", relative);
            audit.put("archivedPath", relative(projectId, target));
            audit.put("sensitive", isSensitivePath(relative));
            appendAudit(projectId, audit);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "archived");
            response.put("sourcePath", relative);
            response.put("archivedPath", relative(projectId, target));
            response.put("audit", audit);
            return response;
        } catch (IOException e) {
            throw new RuntimeException("归档产物失败: " + relative, e);
        }
    }

    public Map<String, Object> restoreArtifact(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        String pathValue = stringValue(request == null ? null : request.get("path"), null);
        String actor = stringValue(request == null ? null : request.get("actor"), "human");
        String reason = stringValue(request == null ? null : request.get("reason"), "");
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }

        Path archived = resolveProjectPath(projectId, pathValue);
        if (!Files.isRegularFile(archived)) {
            throw new ResourceNotFoundException("Archived artifact does not exist: " + pathValue);
        }

        String archivedRelative = relative(projectId, archived);
        String restoreRelative = restorePathFromArchive(archivedRelative);
        if (isGovernanceArtifact(restoreRelative)) {
            throw new IllegalArgumentException("Governance artifacts cannot be restored over governance directories: " + restoreRelative);
        }

        Path restored = projectRoot(projectId).resolve(restoreRelative).normalize();
        if (!restored.startsWith(projectRoot(projectId))) {
            throw new IllegalArgumentException("Restore target escapes project workspace: " + restoreRelative);
        }
        if (Files.exists(restored)) {
            throw new IllegalStateException("Restore target already exists: " + restoreRelative);
        }

        try {
            Files.createDirectories(restored.getParent());
            Files.move(archived, restored);
            cleanupEmptyParents(archived.getParent(), projectRoot(projectId).resolve("artifacts").resolve("archive"));

            Map<String, Object> audit = auditBase(projectId, "restore", actor, reason);
            audit.put("archivedPath", archivedRelative);
            audit.put("restoredPath", restoreRelative);
            audit.put("sensitive", isSensitivePath(restoreRelative));
            appendAudit(projectId, audit);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "restored");
            response.put("archivedPath", archivedRelative);
            response.put("restoredPath", restoreRelative);
            response.put("audit", audit);
            return response;
        } catch (IOException e) {
            throw new RuntimeException("Failed to restore artifact: " + archivedRelative, e);
        }
    }

    public Map<String, Object> deleteArchivedArtifact(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        String pathValue = stringValue(request == null ? null : request.get("path"), null);
        String actor = stringValue(request == null ? null : request.get("actor"), "human");
        String reason = stringValue(request == null ? null : request.get("reason"), "");
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }

        Path archived = resolveProjectPath(projectId, pathValue);
        if (!Files.isRegularFile(archived)) {
            throw new ResourceNotFoundException("Archived artifact does not exist: " + pathValue);
        }

        String archivedRelative = relative(projectId, archived);
        String restoreRelative = restorePathFromArchive(archivedRelative);
        if (isGovernanceArtifact(restoreRelative)) {
            throw new IllegalArgumentException("Governance artifacts cannot be permanently deleted through archive cleanup: " + restoreRelative);
        }

        try {
            long size = Files.size(archived);
            String updatedAt = modifiedAt(archived);
            Files.delete(archived);
            cleanupEmptyParents(archived.getParent(), projectRoot(projectId).resolve("artifacts").resolve("archive"));

            Map<String, Object> audit = auditBase(projectId, "delete_archived", actor, reason);
            audit.put("archivedPath", archivedRelative);
            audit.put("originalPath", restoreRelative);
            audit.put("size", size);
            audit.put("updatedAt", updatedAt);
            audit.put("sensitive", isSensitivePath(restoreRelative));
            appendAudit(projectId, audit);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "deleted");
            response.put("archivedPath", archivedRelative);
            response.put("originalPath", restoreRelative);
            response.put("size", size);
            response.put("audit", audit);
            return response;
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete archived artifact: " + archivedRelative, e);
        }
    }

    public Map<String, Object> diffArtifacts(String projectId, Map<String, Object> request) {
        projectService.getProject(projectId);
        String leftPath = stringValue(request == null ? null : request.get("leftPath"), stringValue(request == null ? null : request.get("left"), null));
        String rightPath = stringValue(request == null ? null : request.get("rightPath"), stringValue(request == null ? null : request.get("right"), null));
        boolean allowSensitive = booleanValue(request == null ? null : request.get("allowSensitive"), false);
        if (leftPath == null || rightPath == null) {
            throw new IllegalArgumentException("leftPath 和 rightPath 不能为空");
        }
        Path left = resolveProjectPath(projectId, leftPath);
        Path right = resolveProjectPath(projectId, rightPath);
        boolean leftSensitive = isSensitiveArtifact(projectId, left);
        boolean rightSensitive = isSensitiveArtifact(projectId, right);
        if (!Files.isRegularFile(left) || !Files.isRegularFile(right)) {
            throw new ResourceNotFoundException("Diff artifact does not exist");
        }
        if (!isTextArtifact(left) || !isTextArtifact(right)) {
            throw new IllegalArgumentException("只支持文本产物差异对比");
        }
        if ((leftSensitive || rightSensitive) && !allowSensitive) {
            appendDiffAudit(projectId, "diff_denied", request, left, right, leftSensitive, rightSensitive, allowSensitive, null, null);
            throw new IllegalArgumentException("敏感样本原文默认禁止差异对比，请显式授权后重试");
        }
        if ((leftSensitive || rightSensitive) && allowSensitive) {
            appendSensitiveAccessAudit(
                projectId,
                "sensitive_diff",
                stringValue(request == null ? null : request.get("actor"), "human"),
                stringValue(request == null ? null : request.get("reason"), ""),
                Map.of(
                    "leftPath", relative(projectId, left),
                    "rightPath", relative(projectId, right),
                    "leftSensitive", leftSensitive,
                    "rightSensitive", rightSensitive
                )
            );
        }

        List<String> leftLines = limitedLines(readText(left));
        List<String> rightLines = limitedLines(readText(right));
        List<Map<String, Object>> diff = lineDiff(leftLines, rightLines);
        long added = diff.stream().filter(item -> "added".equals(item.get("type"))).count();
        long removed = diff.stream().filter(item -> "removed".equals(item.get("type"))).count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("left", artifactSummary(projectId, left));
        response.put("right", artifactSummary(projectId, right));
        response.put("lineLimit", DIFF_LINE_LIMIT);
        response.put("leftTruncated", countLines(readText(left)) > DIFF_LINE_LIMIT);
        response.put("rightTruncated", countLines(readText(right)) > DIFF_LINE_LIMIT);
        response.put("addedLines", added);
        response.put("removedLines", removed);
        response.put("changedLines", added + removed);
        response.put("diff", diff);
        appendDiffAudit(projectId, "diff", request, left, right, leftSensitive, rightSensitive, allowSensitive, added, removed);
        return response;
    }

    public Map<String, Object> listAuditEvents(String projectId, int limit) {
        projectService.getProject(projectId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
        Path auditFile = auditFile(projectId);
        List<Map<String, Object>> items = new ArrayList<>();
        if (Files.exists(auditFile)) {
            try {
                List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && items.size() < safeLimit; i -= 1) {
                    Map<String, Object> item = readJsonMapOrNull(lines.get(i));
                    if (item != null) {
                        items.add(item);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("读取产物审计日志失败", e);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("limit", safeLimit);
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    private Map<String, Object> categorySummary(String projectId, Category category) {
        List<Path> roots = rootsForCategory(projectId, category.key());
        long count = 0;
        long bytes = 0;
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                count += files.size();
                bytes += files.stream().mapToLong(this::fileSize).sum();
            } catch (IOException e) {
                throw new RuntimeException("Failed to summarize artifacts: " + category.key(), e);
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", category.key());
        item.put("title", category.title());
        item.put("roots", roots.stream().map(path -> relativeIfInside(projectId, path)).toList());
        item.put("count", count);
        item.put("bytes", bytes);
        return item;
    }

    private Map<String, Object> artifactSummary(String projectId, Path path) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", relative(projectId, path));
        item.put("name", path.getFileName().toString());
        item.put("category", categoryFor(projectId, path));
        item.put("extension", extension(path));
        item.put("mediaType", mediaType(path).toString());
        item.put("size", fileSize(path));
        item.put("updatedAt", modifiedAt(path));
        item.put("previewable", isTextArtifact(path));
        item.put("sensitive", isSensitiveArtifact(projectId, path));
        return item;
    }

    private List<Path> rootsForCategory(String projectId, String category) {
        Path root = projectRoot(projectId);
        String normalized = category == null || category.isBlank() ? "all" : category;
        return switch (normalized) {
            case "samples" -> List.of(root.resolve("samples"));
            case "analysis" -> List.of(root.resolve("analysis"));
            case "skills" -> List.of(root.resolve("skills"));
            case "novel" -> List.of(root.resolve("novel"), root.resolve("outlines"));
            case "memory" -> List.of(root.resolve("memory"));
            case "graph" -> List.of(root.resolve("graph"), root.resolve("books"));
            case "indexes" -> List.of(root.resolve("indexes"));
            case "logs" -> List.of(root.resolve("logs"));
            case "governance" -> List.of(root.resolve("artifacts").resolve("audit"), root.resolve("artifacts").resolve("archive"));
            case "all" -> categoryDefinitions().stream()
                .flatMap(item -> rootsForCategory(projectId, item.key()).stream())
                .distinct()
                .toList();
            default -> throw new IllegalArgumentException("Unsupported artifact category: " + category);
        };
    }

    private List<Category> categoryDefinitions() {
        return List.of(
            new Category("samples", "样本"),
            new Category("analysis", "分析"),
            new Category("skills", "Skills"),
            new Category("novel", "大纲/正文"),
            new Category("memory", "记忆"),
            new Category("graph", "图谱"),
            new Category("indexes", "索引"),
            new Category("logs", "日志"),
            new Category("governance", "治理")
        );
    }

    private String categoryFor(String projectId, Path path) {
        String relative = relative(projectId, path);
        if (relative.startsWith("samples/")) return "samples";
        if (relative.startsWith("analysis/")) return "analysis";
        if (relative.startsWith("skills/")) return "skills";
        if (relative.startsWith("novel/") || relative.startsWith("outlines/")) return "novel";
        if (relative.startsWith("memory/")) return "memory";
        if (relative.startsWith("graph/") || relative.startsWith("books/")) return "graph";
        if (relative.startsWith("indexes/")) return "indexes";
        if (relative.startsWith("logs/")) return "logs";
        if (relative.startsWith("artifacts/audit/") || relative.startsWith("artifacts/archive/")) return "governance";
        return "other";
    }

    private Path resolveProjectPath(String projectId, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalArgumentException("Artifact path is required");
        }
        Path root = projectRoot(projectId);
        Path resolved = root.resolve(pathValue).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Artifact path escapes project workspace: " + pathValue);
        }
        return resolved;
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private String relativeIfInside(String projectId, Path path) {
        Path root = projectRoot(projectId);
        Path normalized = path.normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString().replace("\\", "/") : normalized.toString();
    }

    private String modifiedAt(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private boolean isTextArtifact(Path path) {
        return TEXT_EXTENSIONS.contains(extension(path));
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index + 1).toLowerCase(Locale.ROOT) : "";
    }

    private MediaType mediaType(Path path) {
        return switch (extension(path)) {
            case "json" -> MediaType.APPLICATION_JSON;
            case "md", "txt", "log", "csv", "yaml", "yml" -> MediaType.TEXT_PLAIN;
            case "html" -> MediaType.TEXT_HTML;
            case "xml", "graphml" -> MediaType.APPLICATION_XML;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read artifact: " + file.getFileName(), e);
        }
    }

    private boolean isSensitiveArtifact(String projectId, Path path) {
        return isSensitivePath(relative(projectId, path));
    }

    private boolean isSensitivePath(String relative) {
        return relative.startsWith("samples/raw/")
            || relative.startsWith("samples/normalized/")
            || relative.startsWith("samples/chunks/");
    }

    private boolean isGovernanceArtifact(String relative) {
        return relative.startsWith("artifacts/audit/") || relative.startsWith("artifacts/archive/");
    }

    private String restorePathFromArchive(String archivedRelative) {
        String prefix = "artifacts/archive/";
        if (!archivedRelative.startsWith(prefix)) {
            throw new IllegalArgumentException("Artifact is not in archive: " + archivedRelative);
        }
        String remainder = archivedRelative.substring(prefix.length());
        int separator = remainder.indexOf('/');
        if (separator <= 0 || separator == remainder.length() - 1) {
            throw new IllegalArgumentException("Archived artifact path is missing original location: " + archivedRelative);
        }
        return remainder.substring(separator + 1);
    }

    private void cleanupEmptyParents(Path start, Path stop) {
        Path current = start;
        while (current != null && current.startsWith(stop) && !current.equals(stop)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
    }

    private Map<String, Object> auditBase(String projectId, String action, String actor, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", "artifact_event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        item.put("projectId", projectId);
        item.put("action", action);
        item.put("actor", actor == null || actor.isBlank() ? "human" : actor);
        item.put("reason", reason == null ? "" : reason);
        item.put("createdAt", LocalDateTime.now().toString());
        return item;
    }

    private void appendSensitiveAccessAudit(
            String projectId,
            String action,
            String actor,
            String reason,
            Map<String, Object> details) {
        Map<String, Object> audit = auditBase(projectId, action, actor, reason);
        audit.put("sensitive", true);
        audit.put("details", details == null ? Map.of() : details);
        appendAudit(projectId, audit);
    }

    private void appendArtifactAccessAudit(
            String projectId,
            String action,
            String actor,
            String reason,
            Path file,
            boolean sensitive,
            Map<String, Object> details) {
        Map<String, Object> audit = auditBase(projectId, action, actor, reason);
        audit.put("path", relative(projectId, file));
        audit.put("sensitive", sensitive);
        audit.put("details", details == null ? Map.of() : details);
        appendAudit(projectId, audit);
    }

    private void appendDiffAudit(
            String projectId,
            String action,
            Map<String, Object> request,
            Path left,
            Path right,
            boolean leftSensitive,
            boolean rightSensitive,
            boolean allowSensitive,
            Long added,
            Long removed) {
        Map<String, Object> audit = auditBase(
            projectId,
            action,
            stringValue(request == null ? null : request.get("actor"), "human"),
            stringValue(request == null ? null : request.get("reason"), "")
        );
        audit.put("leftPath", relative(projectId, left));
        audit.put("rightPath", relative(projectId, right));
        audit.put("sensitive", leftSensitive || rightSensitive);
        audit.put("leftSensitive", leftSensitive);
        audit.put("rightSensitive", rightSensitive);
        audit.put("allowSensitive", allowSensitive);
        if (added != null && removed != null) {
            audit.put("addedLines", added);
            audit.put("removedLines", removed);
            audit.put("changedLines", added + removed);
        }
        appendAudit(projectId, audit);
    }

    private void appendAudit(String projectId, Map<String, Object> event) {
        Path auditFile = auditFile(projectId);
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(
                auditFile,
                toJson(event) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                Files.exists(auditFile)
                    ? java.nio.file.StandardOpenOption.APPEND
                    : java.nio.file.StandardOpenOption.CREATE
            );
        } catch (IOException e) {
            throw new RuntimeException("写入产物审计日志失败", e);
        }
    }

    private Path auditFile(String projectId) {
        return projectRoot(projectId).resolve("artifacts").resolve("audit").resolve("artifact_events.jsonl");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化产物治理数据失败", e);
        }
    }

    private Map<String, Object> skip(String path, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", path);
        item.put("reason", reason);
        return item;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item))
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> limitedLines(String text) {
        String[] lines = text.split("\\R", -1);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.length && i < DIFF_LINE_LIMIT; i += 1) {
            result.add(lines[i]);
        }
        return result;
    }

    private int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return text.split("\\R", -1).length;
    }

    private List<Map<String, Object>> lineDiff(List<String> left, List<String> right) {
        int[][] lcs = new int[left.size() + 1][right.size() + 1];
        for (int i = left.size() - 1; i >= 0; i -= 1) {
            for (int j = right.size() - 1; j >= 0; j -= 1) {
                if (left.get(i).equals(right.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<Map<String, Object>> diff = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            if (left.get(i).equals(right.get(j))) {
                diff.add(diffLine("unchanged", i + 1, j + 1, left.get(i)));
                i += 1;
                j += 1;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                diff.add(diffLine("removed", i + 1, null, left.get(i)));
                i += 1;
            } else {
                diff.add(diffLine("added", null, j + 1, right.get(j)));
                j += 1;
            }
        }
        while (i < left.size()) {
            diff.add(diffLine("removed", i + 1, null, left.get(i)));
            i += 1;
        }
        while (j < right.size()) {
            diff.add(diffLine("added", null, j + 1, right.get(j)));
            j += 1;
        }
        return diff;
    }

    private Map<String, Object> diffLine(String type, Integer leftLine, Integer rightLine, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("leftLine", leftLine);
        item.put("rightLine", rightLine);
        item.put("text", text);
        return item;
    }

    private Object readJsonOrNull(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, Object> readJsonMapOrNull(String text) {
        try {
            return objectMapper.readValue(text, new TypeReference<HashMap<String, Object>>() {});
        } catch (IOException e) {
            return null;
        }
    }

    private record Category(String key, String title) {}

    public record DownloadedArtifact(String filename, MediaType mediaType, ByteArrayResource resource) {}
}
