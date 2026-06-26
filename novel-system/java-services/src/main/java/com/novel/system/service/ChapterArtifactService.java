package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.ChapterArtifact;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.ChapterArtifactRepository;
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
public class ChapterArtifactService {

    private final ProjectService projectService;
    private final ChapterArtifactRepository chapterArtifactRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Transactional
    public Map<String, Object> syncChaptersFromWorkspace(String projectId, String bookId) {
        projectService.getProject(projectId);
        validateBookId(bookId);
        chapterArtifactRepository.deleteByProjectIdAndBookId(projectId, bookId);

        List<ChapterArtifact> entities = chapterFiles(projectId, bookId).stream()
            .map(path -> readChapterArtifact(projectId, bookId, path))
            .toList();
        List<ChapterArtifact> saved = chapterArtifactRepository.saveAll(entities);
        log.info("Synced chapter artifacts to DB: project={}, book={}, chapters={}", projectId, bookId, saved.size());
        return buildSummary(projectId, bookId, saved);
    }

    @Transactional
    public Map<String, Object> syncChapterFromWorkspace(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            String requestedStage) {
        projectService.getProject(projectId);
        validateBookId(bookId);
        Path chapterFile = resolveChapterFile(projectId, bookId, volumeNumber, chapterNumber, requestedStage);
        ChapterArtifact saved = chapterArtifactRepository.save(readChapterArtifact(projectId, bookId, chapterFile));
        return response(saved, true);
    }

    public Map<String, Object> listChapters(String projectId, String bookId) {
        projectService.getProject(projectId);
        validateBookId(bookId);
        List<ChapterArtifact> chapters = chapterArtifactRepository
            .findByProjectIdAndBookIdOrderByVolumeNumberAscChapterNumberAscStageAsc(projectId, bookId);
        return buildSummary(projectId, bookId, chapters);
    }

    public Map<String, Object> getChapter(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            String requestedStage) {
        projectService.getProject(projectId);
        validateBookId(bookId);
        String stage = normalizeStage(requestedStage);
        ChapterArtifact entity;
        if ("auto".equals(stage)) {
            List<ChapterArtifact> chapters = chapterArtifactRepository
                .findByProjectIdAndBookIdAndVolumeNumberAndChapterNumberOrderByStageAsc(
                    projectId,
                    bookId,
                    volumeNumber,
                    chapterNumber
                );
            entity = chapters.stream()
                .filter(item -> "final".equals(item.getStage()))
                .findFirst()
                .orElseGet(() -> chapters.stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("数据库章节不存在，请先同步")));
        } else {
            entity = chapterArtifactRepository
                .findByProjectIdAndBookIdAndVolumeNumberAndChapterNumberAndStage(
                    projectId,
                    bookId,
                    volumeNumber,
                    chapterNumber,
                    stage
                )
                .orElseThrow(() -> new ResourceNotFoundException("数据库章节不存在，请先同步: " + bookId + "/" + volumeNumber + "/" + chapterNumber + "/" + stage));
        }
        return response(entity, true);
    }

    private ChapterArtifact readChapterArtifact(String projectId, String fallbackBookId, Path chapterFile) {
        Map<String, Object> raw = readJson(chapterFile);
        String bookId = stringValue(valueOf(raw, "book_id", "bookId"), fallbackBookId);
        Integer volumeNumber = firstInt(valueOf(raw, "volume_number", "volumeNumber"), extractVolumeNumber(chapterFile));
        Integer chapterNumber = firstInt(valueOf(raw, "chapter_number", "chapterNumber"), extractChapterNumber(chapterFile));
        String stage = stageForPath(projectId, chapterFile, stringValue(valueOf(raw, "stage", "stage"), ""));
        String id = id(projectId, bookId, volumeNumber, chapterNumber, stage);

        ChapterArtifact entity = chapterArtifactRepository.findById(id).orElseGet(ChapterArtifact::new);
        Map<String, Object> boundaryCheck = mapValue(valueOf(raw, "boundary_check", "boundaryCheck"));
        Path latestReviewFile = latestReviewFile(projectId, bookId, volumeNumber, chapterNumber).orElse(null);
        Map<String, Object> latestReview = latestReviewFile == null ? new LinkedHashMap<>() : readJson(latestReviewFile);

        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setBookId(bookId);
        entity.setChapterId(stringValue(valueOf(raw, "chapter_id", "chapterId"), stripSuffix(chapterFile.getFileName().toString(), ".json")));
        entity.setVolumeNumber(volumeNumber);
        entity.setChapterNumber(chapterNumber);
        entity.setChapterTitle(stringValue(valueOf(raw, "chapter_title", "chapterTitle"), ""));
        entity.setStage(stage);
        entity.setStatus(resolveStatus(raw, stage));
        entity.setReviewStatus(stringValue(valueOf(raw, "review_status", "reviewStatus"), ""));
        entity.setHumanReviewStatus(stringValue(valueOf(raw, "human_review_status", "humanReviewStatus"), ""));
        entity.setVersion(firstInt(valueOf(raw, "version", "version")));
        entity.setWordCount(firstInt(valueOf(raw, "word_count", "wordCount"), stringValue(valueOf(raw, "content", "content"), "").length()));
        entity.setQualityScore(toDouble(valueOf(raw, "quality_score", "qualityScore")));
        entity.setNeedsRevision(booleanValue(valueOf(raw, "needs_revision", "needsRevision")));
        entity.setBoundaryPassed(booleanValue(boundaryCheck.get("passed")));
        entity.setBoundaryErrorCount(listSize(boundaryCheck.get("blocking_errors")));
        entity.setBoundaryWarningCount(listSize(boundaryCheck.get("warnings")));
        entity.setChapterPath(relative(projectId, chapterFile));
        entity.setTextPath(textPath(projectId, chapterFile));
        entity.setContextPackPath(stringValue(valueOf(raw, "context_pack_path", "contextPackPath"), ""));
        entity.setSourceDraftPath(stringValue(valueOf(raw, "source_draft_path", "sourceDraftPath"), ""));
        entity.setFinalPath(stringValue(valueOf(raw, "final_path", "finalPath"), ""));
        entity.setLatestReviewPath(latestReviewFile == null ? null : relative(projectId, latestReviewFile));
        entity.setContent(stringValue(valueOf(raw, "content", "content"), ""));
        entity.setChapter(raw);
        entity.setBoundaryCheck(boundaryCheck);
        entity.setRevisionHistory(listOfMaps(valueOf(raw, "revision_history", "revisionHistory")));
        entity.setHumanReviewHistory(listOfMaps(valueOf(raw, "human_review_history", "humanReviewHistory")));
        entity.setManualEditHistory(listOfMaps(valueOf(raw, "manual_edit_history", "manualEditHistory")));
        entity.setLatestReview(latestReview);
        entity.setChapterMetadata(metadata(projectId, bookId, chapterFile, latestReviewFile, raw, stage));
        entity.setChapterCreatedAt(parseDateTime(valueOf(raw, "created_at", "createdAt")));
        entity.setChapterUpdatedAt(parseDateTime(valueOf(raw, "updated_at", "updatedAt")));
        entity.setFinalizedAt(parseDateTime(valueOf(raw, "finalized_at", "finalizedAt")));
        entity.setSyncedAt(LocalDateTime.now());
        return entity;
    }

    private Map<String, Object> buildSummary(String projectId, String bookId, List<ChapterArtifact> chapters) {
        long draftCount = chapters.stream().filter(chapter -> "draft".equals(chapter.getStage())).count();
        long finalCount = chapters.stream().filter(chapter -> "final".equals(chapter.getStage())).count();
        long needsRevisionCount = chapters.stream().filter(chapter -> Boolean.TRUE.equals(chapter.getNeedsRevision())).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("bookId", bookId);
        result.put("dbChapterArtifactCount", chapters.size());
        result.put("draftCount", draftCount);
        result.put("finalCount", finalCount);
        result.put("needsRevisionCount", needsRevisionCount);
        result.put("chapters", chapters.stream().map(chapter -> response(chapter, false)).toList());
        return result;
    }

    private Map<String, Object> response(ChapterArtifact entity, boolean includeContent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("projectId", entity.getProjectId());
        result.put("bookId", entity.getBookId());
        result.put("chapterId", entity.getChapterId());
        result.put("volumeNumber", entity.getVolumeNumber());
        result.put("chapterNumber", entity.getChapterNumber());
        result.put("chapterTitle", entity.getChapterTitle());
        result.put("stage", entity.getStage());
        result.put("status", entity.getStatus());
        result.put("reviewStatus", entity.getReviewStatus());
        result.put("humanReviewStatus", entity.getHumanReviewStatus());
        result.put("version", entity.getVersion());
        result.put("wordCount", entity.getWordCount());
        result.put("qualityScore", entity.getQualityScore());
        result.put("needsRevision", entity.getNeedsRevision());
        result.put("boundaryPassed", entity.getBoundaryPassed());
        result.put("boundaryErrorCount", entity.getBoundaryErrorCount());
        result.put("boundaryWarningCount", entity.getBoundaryWarningCount());
        result.put("chapterPath", entity.getChapterPath());
        result.put("textPath", entity.getTextPath());
        result.put("contextPackPath", entity.getContextPackPath());
        result.put("sourceDraftPath", entity.getSourceDraftPath());
        result.put("finalPath", entity.getFinalPath());
        result.put("latestReviewPath", entity.getLatestReviewPath());
        result.put("chapterCreatedAt", entity.getChapterCreatedAt());
        result.put("chapterUpdatedAt", entity.getChapterUpdatedAt());
        result.put("finalizedAt", entity.getFinalizedAt());
        result.put("syncedAt", entity.getSyncedAt());
        result.put("createdAt", entity.getCreatedAt());
        result.put("updatedAt", entity.getUpdatedAt());
        if (includeContent) {
            result.put("content", entity.getContent());
            result.put("chapter", entity.getChapter());
            result.put("boundaryCheck", entity.getBoundaryCheck());
            result.put("revisionHistory", entity.getRevisionHistory());
            result.put("humanReviewHistory", entity.getHumanReviewHistory());
            result.put("manualEditHistory", entity.getManualEditHistory());
            result.put("latestReview", entity.getLatestReview());
            result.put("chapterMetadata", entity.getChapterMetadata());
        }
        return result;
    }

    private Map<String, Object> metadata(
            String projectId,
            String bookId,
            Path chapterFile,
            Path latestReviewFile,
            Map<String, Object> raw,
            String stage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("bookId", bookId);
        metadata.put("stage", stage);
        metadata.put("chapterModifiedAt", modifiedAt(chapterFile));
        metadata.put("latestReviewModifiedAt", latestReviewFile == null ? null : modifiedAt(latestReviewFile));
        metadata.put("usedSkillCount", listSize(valueOf(raw, "used_skills", "usedSkills")));
        metadata.put("referencedChapterCount", listSize(valueOf(raw, "referenced_chapters", "referencedChapters")));
        metadata.put("revisionCount", listSize(valueOf(raw, "revision_history", "revisionHistory")));
        metadata.put("humanReviewCount", listSize(valueOf(raw, "human_review_history", "humanReviewHistory")));
        metadata.put("manualEditCount", listSize(valueOf(raw, "manual_edit_history", "manualEditHistory")));
        metadata.put("hasBoundaryCheck", !mapValue(valueOf(raw, "boundary_check", "boundaryCheck")).isEmpty());
        return metadata;
    }

    private List<Path> chapterFiles(String projectId, String bookId) {
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(
            projectRoot(projectId).resolve("novel").resolve("chapters").resolve("final").resolve(bookId),
            projectRoot(projectId).resolve("novel").resolve("chapters").resolve("drafts").resolve(bookId),
            projectRoot(projectId).resolve("books").resolve(bookId)
        )) {
            if (!Files.exists(dir)) {
                continue;
            }
            try (var stream = Files.walk(dir)) {
                stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("chapter_\\d+\\.json"))
                    .forEach(files::add);
            } catch (IOException e) {
                throw new RuntimeException("读取章节目录失败", e);
            }
        }
        files.sort(Comparator
            .comparing((Path path) -> extractVolumeNumber(path), Comparator.nullsLast(Integer::compareTo))
            .thenComparing(path -> extractChapterNumber(path), Comparator.nullsLast(Integer::compareTo))
            .thenComparing(path -> stageForPath(projectId, path, "")));
        return files;
    }

    private Path resolveChapterFile(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            String requestedStage) {
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }
        String stage = normalizeStage(requestedStage);
        List<Path> candidates = new ArrayList<>();
        if ("auto".equals(stage) || "final".equals(stage)) {
            candidates.add(finalChapterFile(projectId, bookId, volumeNumber, chapterNumber));
        }
        if ("auto".equals(stage) || "draft".equals(stage)) {
            candidates.add(draftChapterFile(projectId, bookId, volumeNumber, chapterNumber));
        }
        candidates.add(projectRoot(projectId).resolve("books").resolve(bookId).resolve("volume_" + volumeNumber)
            .resolve("chapters").resolve("chapter_" + chapterNumber + ".json"));
        return candidates.stream()
            .filter(Files::exists)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("章节不存在: " + bookId + "/" + volumeNumber + "/" + chapterNumber + "/" + stage));
    }

    private Optional<Path> latestReviewFile(String projectId, String bookId, Integer volumeNumber, Integer chapterNumber) {
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(bookId).resolve("volume_" + volumeNumber);
        if (!Files.exists(reviewsDir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(reviewsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("chapter_" + chapterNumber + "_.*\\.json"))
                .max(Comparator.comparing(this::modifiedAt));
        } catch (IOException e) {
            throw new RuntimeException("读取章节审查目录失败", e);
        }
    }

    private Path draftChapterFile(String projectId, String bookId, Integer volumeNumber, Integer chapterNumber) {
        return projectRoot(projectId).resolve("novel").resolve("chapters").resolve("drafts")
            .resolve(bookId).resolve("volume_" + volumeNumber).resolve("chapter_" + chapterNumber + ".json");
    }

    private Path finalChapterFile(String projectId, String bookId, Integer volumeNumber, Integer chapterNumber) {
        return projectRoot(projectId).resolve("novel").resolve("chapters").resolve("final")
            .resolve(bookId).resolve("volume_" + volumeNumber).resolve("chapter_" + chapterNumber + ".json");
    }

    private String stageForPath(String projectId, Path chapterFile, String fallbackStage) {
        Path finalRoot = projectRoot(projectId).resolve("novel").resolve("chapters").resolve("final").normalize();
        Path draftRoot = projectRoot(projectId).resolve("novel").resolve("chapters").resolve("drafts").normalize();
        Path normalized = chapterFile.normalize();
        if (normalized.startsWith(finalRoot)) {
            return "final";
        }
        if (normalized.startsWith(draftRoot)) {
            return "draft";
        }
        return normalizeStage(fallbackStage == null || fallbackStage.isBlank() ? "draft" : fallbackStage);
    }

    private String normalizeStage(String requestedStage) {
        String stage = requestedStage == null || requestedStage.isBlank() ? "auto" : requestedStage.toLowerCase();
        return switch (stage) {
            case "draft", "final", "auto" -> stage;
            default -> throw new IllegalArgumentException("stage 仅支持 draft/final/auto");
        };
    }

    private String resolveStatus(Map<String, Object> raw, String stage) {
        Object status = valueOf(raw, "status", "status");
        if (status != null && !String.valueOf(status).isBlank()) {
            return String.valueOf(status);
        }
        return "final".equals(stage) ? "finalized" : "completed";
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private String textPath(String projectId, Path chapterFile) {
        Path textFile = chapterFile.resolveSibling(stripSuffix(chapterFile.getFileName().toString(), ".json") + ".txt");
        return Files.exists(textFile) ? relative(projectId, textFile) : null;
    }

    private Map<String, Object> readJson(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取章节JSON失败: " + file.getFileName(), e);
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

    private LocalDateTime parseDateTime(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String text = value.toString().trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    private Object valueOf(Map<String, Object> map, String snakeKey, String camelKey) {
        if (map.containsKey(snakeKey)) {
            return map.get(snakeKey);
        }
        return map.get(camelKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
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

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private Integer extractVolumeNumber(Path path) {
        for (Path part : path) {
            String text = part.toString();
            if (text.matches("volume_\\d+")) {
                return toInteger(text.substring("volume_".length()));
            }
        }
        return null;
    }

    private Integer extractChapterNumber(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.matches("chapter_\\d+\\.json")) {
            return toInteger(fileName.substring("chapter_".length(), fileName.length() - ".json".length()));
        }
        return null;
    }

    private String id(String projectId, String bookId, Integer volumeNumber, Integer chapterNumber, String stage) {
        return projectId + ":" + bookId + ":v" + volumeNumber + ":c" + chapterNumber + ":" + stage;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private void validateBookId(String bookId) {
        if (bookId == null || bookId.isBlank() || "default".equals(bookId)) {
            throw new IllegalArgumentException("章节 DB 接口需要明确 bookId");
        }
    }
}
