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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ArtifactService {

    private static final long PREVIEW_TEXT_LIMIT = 200_000L;
    private static final long LIST_FILE_SIZE_LIMIT = 50L * 1024L * 1024L;
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
        projectService.getProject(projectId);
        Path file = resolveProjectPath(projectId, pathValue);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("Artifact does not exist: " + pathValue);
        }

        Map<String, Object> response = artifactSummary(projectId, file);
        if (isTextArtifact(file)) {
            String content = readText(file);
            response.put("content", content.length() > PREVIEW_TEXT_LIMIT ? content.substring(0, (int) PREVIEW_TEXT_LIMIT) : content);
            response.put("truncated", content.length() > PREVIEW_TEXT_LIMIT);
            if (extension(file).equals("json")) {
                response.put("json", readJsonOrNull(file));
            }
        } else {
            response.put("content", null);
            response.put("truncated", false);
        }
        return response;
    }

    public DownloadedArtifact downloadArtifact(String projectId, String pathValue) {
        projectService.getProject(projectId);
        Path file = resolveProjectPath(projectId, pathValue);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("Artifact does not exist: " + pathValue);
        }
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
            new Category("logs", "日志")
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

    private Object readJsonOrNull(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return null;
        }
    }

    private record Category(String key, String title) {}

    public record DownloadedArtifact(String filename, MediaType mediaType, ByteArrayResource resource) {}
}
