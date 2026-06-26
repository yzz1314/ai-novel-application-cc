package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.OutlineArtifact;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.OutlineArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutlineArtifactService {

    private final ProjectService projectService;
    private final OutlineArtifactRepository outlineArtifactRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Transactional
    public Map<String, Object> syncOutlineFromWorkspace(String projectId, String requestedBookId) {
        projectService.getProject(projectId);
        Path outlineFile = resolveOutlineFile(projectId, requestedBookId);
        Map<String, Object> outline = readJson(outlineFile);
        String bookId = stringValue(outline.get("book_id"), stripSuffix(outlineFile.getFileName().toString(), "_outline.json"));

        OutlineArtifact entity = outlineArtifactRepository
            .findByProjectIdAndBookId(projectId, bookId)
            .orElseGet(OutlineArtifact::new);

        Path soulFile = projectRoot(projectId).resolve("novel").resolve("soul").resolve("project_soul.md");
        Path latestReviewFile = latestReviewFile(projectId, bookId).orElse(null);
        Map<String, Object> latestReview = latestReviewFile != null ? readJson(latestReviewFile) : new LinkedHashMap<>();
        OutlineStats stats = collectStats(outline);
        LocalDateTime now = LocalDateTime.now();

        entity.setId(projectId + ":" + bookId);
        entity.setProjectId(projectId);
        entity.setBookId(bookId);
        entity.setBookTitle(stringValue(outline.get("book_title"), bookId));
        entity.setGenre(stringValue(outline.get("genre"), ""));
        entity.setTargetWordCount(firstInt(outline.get("target_word_count"), outline.get("targetWordCount")));
        entity.setTotalVolumes(firstInt(outline.get("total_volumes"), stats.volumeCount()));
        entity.setTotalChapters(firstInt(outline.get("total_chapters"), stats.chapterCount()));
        entity.setChaptersWithBoundary(stats.chaptersWithBoundary());
        entity.setStatus(resolveStatus(outline, latestReview));
        entity.setReviewStatus(stringValue(firstPresent(latestReview.get("status"), latestReview.get("review_status")), ""));
        entity.setLatestReviewScore(firstInt(latestReview.get("score"), latestReview.get("overall_score")));
        entity.setOutlinePath(relative(projectId, outlineFile));
        entity.setProjectSoulPath(Files.exists(soulFile) ? relative(projectId, soulFile) : null);
        entity.setLatestReviewPath(latestReviewFile != null ? relative(projectId, latestReviewFile) : null);
        entity.setOutline(outline);
        entity.setProjectSoul(Files.exists(soulFile) ? readText(soulFile) : null);
        entity.setLatestReview(latestReview);
        entity.setOutlineMetadata(metadata(projectId, bookId, outlineFile, soulFile, latestReviewFile, stats));
        entity.setSyncedAt(now);

        OutlineArtifact saved = outlineArtifactRepository.save(entity);
        log.info("Synced outline artifact to DB: project={}, book={}, chapters={}/{}",
            projectId, bookId, stats.chaptersWithBoundary(), stats.chapterCount());
        return response(saved);
    }

    public List<Map<String, Object>> listOutlines(String projectId) {
        projectService.getProject(projectId);
        return outlineArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(projectId)
            .stream()
            .map(this::summary)
            .toList();
    }

    public Map<String, Object> getOutline(String projectId, String requestedBookId) {
        projectService.getProject(projectId);
        String bookId = resolveBookIdFromDbOrWorkspace(projectId, requestedBookId);
        OutlineArtifact entity = outlineArtifactRepository.findByProjectIdAndBookId(projectId, bookId)
            .orElseThrow(() -> new ResourceNotFoundException("数据库大纲不存在，请先同步: " + bookId));
        return response(entity);
    }

    public Optional<OutlineArtifact> findOutline(String projectId, String requestedBookId) {
        String bookId = resolveBookIdFromDbOrWorkspace(projectId, requestedBookId);
        return outlineArtifactRepository.findByProjectIdAndBookId(projectId, bookId);
    }

    private Map<String, Object> response(OutlineArtifact entity) {
        Map<String, Object> result = summary(entity);
        result.put("outline", entity.getOutline());
        result.put("projectSoul", entity.getProjectSoul());
        result.put("latestReview", entity.getLatestReview());
        result.put("outlineMetadata", entity.getOutlineMetadata());
        return result;
    }

    private Map<String, Object> summary(OutlineArtifact entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("projectId", entity.getProjectId());
        result.put("bookId", entity.getBookId());
        result.put("bookTitle", entity.getBookTitle());
        result.put("genre", entity.getGenre());
        result.put("targetWordCount", entity.getTargetWordCount());
        result.put("totalVolumes", entity.getTotalVolumes());
        result.put("totalChapters", entity.getTotalChapters());
        result.put("chaptersWithBoundary", entity.getChaptersWithBoundary());
        result.put("status", entity.getStatus());
        result.put("reviewStatus", entity.getReviewStatus());
        result.put("latestReviewScore", entity.getLatestReviewScore());
        result.put("outlinePath", entity.getOutlinePath());
        result.put("projectSoulPath", entity.getProjectSoulPath());
        result.put("latestReviewPath", entity.getLatestReviewPath());
        result.put("syncedAt", entity.getSyncedAt());
        result.put("createdAt", entity.getCreatedAt());
        result.put("updatedAt", entity.getUpdatedAt());
        return result;
    }

    private Map<String, Object> metadata(
            String projectId,
            String bookId,
            Path outlineFile,
            Path soulFile,
            Path latestReviewFile,
            OutlineStats stats) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("bookId", bookId);
        metadata.put("volumeCount", stats.volumeCount());
        metadata.put("chapterCount", stats.chapterCount());
        metadata.put("chaptersWithBoundary", stats.chaptersWithBoundary());
        metadata.put("chaptersMissingBoundary", Math.max(0, stats.chapterCount() - stats.chaptersWithBoundary()));
        metadata.put("outlineModifiedAt", modifiedAt(outlineFile));
        metadata.put("soulModifiedAt", Files.exists(soulFile) ? modifiedAt(soulFile) : null);
        metadata.put("latestReviewModifiedAt", latestReviewFile != null ? modifiedAt(latestReviewFile) : null);
        metadata.put("hasProjectSoul", Files.exists(soulFile));
        metadata.put("hasLatestReview", latestReviewFile != null);
        return metadata;
    }

    private OutlineStats collectStats(Map<String, Object> outline) {
        Object volumesValue = firstPresent(outline.get("volumes"), outline.get("volume_outlines"));
        if (!(volumesValue instanceof List<?> volumes)) {
            return new OutlineStats(0, 0, 0);
        }

        int chapterCount = 0;
        int chaptersWithBoundary = 0;
        for (Object volumeValue : volumes) {
            if (!(volumeValue instanceof Map<?, ?> volume)) {
                continue;
            }
            Object chaptersValue = firstPresent(volume.get("chapters"), volume.get("chapter_outlines"));
            if (!(chaptersValue instanceof List<?> chapters)) {
                continue;
            }
            for (Object chapterValue : chapters) {
                if (!(chapterValue instanceof Map<?, ?> chapter)) {
                    continue;
                }
                chapterCount += 1;
                if (hasBoundary(chapter)) {
                    chaptersWithBoundary += 1;
                }
            }
        }
        return new OutlineStats(volumes.size(), chapterCount, chaptersWithBoundary);
    }

    private boolean hasBoundary(Map<?, ?> chapter) {
        return !textValue(chapter, "core_goal").isBlank()
            && !listValue(chapter, "must_write").isEmpty()
            && !listValue(chapter, "allowed_progress").isEmpty()
            && !listValue(chapter, "must_not_write").isEmpty()
            && !mapValue(chapter, "reserved_for_future").isEmpty()
            && !textValue(chapter, "stop_point").isBlank()
            && !textValue(chapter, "ending_hook").isBlank();
    }

    private String resolveStatus(Map<String, Object> outline, Map<String, Object> latestReview) {
        Object explicit = firstPresent(outline.get("status"), outline.get("outline_status"));
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit);
        }
        String reviewStatus = stringValue(firstPresent(latestReview.get("status"), latestReview.get("review_status")), "");
        if ("passed".equalsIgnoreCase(reviewStatus) || "approved".equalsIgnoreCase(reviewStatus)) {
            return "reviewed";
        }
        if ("needs_revision".equalsIgnoreCase(reviewStatus)) {
            return "needs_revision";
        }
        return "generated";
    }

    private String resolveBookIdFromDbOrWorkspace(String projectId, String requestedBookId) {
        if (requestedBookId != null && !requestedBookId.isBlank() && !"default".equals(requestedBookId)) {
            return requestedBookId;
        }
        List<OutlineArtifact> dbOutlines = outlineArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
        if (!dbOutlines.isEmpty()) {
            return dbOutlines.get(0).getBookId();
        }
        Path outlineFile = latestOutlineFile(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("项目尚未生成大纲"));
        Map<String, Object> outline = readJson(outlineFile);
        return stringValue(outline.get("book_id"), stripSuffix(outlineFile.getFileName().toString(), "_outline.json"));
    }

    private Path resolveOutlineFile(String projectId, String requestedBookId) {
        String bookId = requestedBookId;
        if (bookId == null || bookId.isBlank() || "default".equals(bookId)) {
            return latestOutlineFile(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("项目尚未生成大纲"));
        }
        List<Path> candidates = List.of(
            projectRoot(projectId).resolve("novel").resolve("outline").resolve(bookId + "_outline.json"),
            projectRoot(projectId).resolve("outlines").resolve(bookId + "_outline.json")
        );
        return candidates.stream()
            .filter(Files::exists)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("大纲不存在: " + bookId));
    }

    private List<Path> outlineFiles(String projectId) {
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
        files.sort(Comparator
            .comparing((Path path) -> isStandardOutlinePath(projectId, path) ? 0 : 1)
            .thenComparing(this::modifiedAt, Comparator.reverseOrder()));
        return files;
    }

    private Optional<Path> latestOutlineFile(String projectId) {
        return outlineFiles(projectId).stream().findFirst();
    }

    private Optional<Path> latestReviewFile(String projectId, String bookId) {
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(bookId).resolve("outline");
        if (!Files.exists(reviewsDir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(reviewsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("outline_.*\\.json"))
                .max(Comparator.comparing(this::modifiedAt));
        } catch (IOException e) {
            throw new RuntimeException("读取大纲审查目录失败", e);
        }
    }

    private boolean isStandardOutlinePath(String projectId, Path path) {
        Path standardDir = projectRoot(projectId).resolve("novel").resolve("outline").normalize();
        return path.normalize().startsWith(standardDir);
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
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

    private Integer firstInt(Object... values) {
        for (Object value : values) {
            Integer parsed = toInteger(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private LocalDateTime parseDateTime(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object valueFrom(Map<?, ?> map, String snakeKey) {
        Object value = map.get(snakeKey);
        if (value != null) {
            return value;
        }
        return map.get(toCamelCase(snakeKey));
    }

    private String textValue(Map<?, ?> map, String snakeKey) {
        Object value = valueFrom(map, snakeKey);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<?> listValue(Map<?, ?> map, String snakeKey) {
        Object value = valueFrom(map, snakeKey);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(item -> item != null && !String.valueOf(item).trim().isBlank())
                .toList();
        }
        if (value == null || String.valueOf(value).trim().isBlank()) {
            return List.of();
        }
        return List.of(value);
    }

    private Map<?, ?> mapValue(Map<?, ?> map, String snakeKey) {
        Object value = valueFrom(map, snakeKey);
        if (value instanceof Map<?, ?> childMap) {
            return childMap.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !String.valueOf(entry.getKey()).trim().isBlank())
                .filter(entry -> entry.getValue() != null && !String.valueOf(entry.getValue()).trim().isBlank())
                .collect(
                    LinkedHashMap::new,
                    (target, entry) -> target.put(entry.getKey(), entry.getValue()),
                    LinkedHashMap::putAll
                );
        }
        return Map.of();
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

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private record OutlineStats(int volumeCount, int chapterCount, int chaptersWithBoundary) {
    }
}
