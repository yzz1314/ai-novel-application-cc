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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BookArtifactService {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final DateTimeFormatter SNAPSHOT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private record ChapterComparison(String kind, String stage, String versionId, Path path, Map<String, Object> chapter) {}

    private final ProjectService projectService;
    private final TaskExecutorService taskExecutorService;
    private final ChapterArtifactService chapterArtifactService;
    private final OutlineArtifactService outlineArtifactService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    public List<Map<String, Object>> listBooks(String projectId) {
        projectService.getProject(projectId);
        List<Map<String, Object>> books = new ArrayList<>();
        List<String> seenBookIds = new ArrayList<>();

        for (Path outlineFile : outlineFiles(projectId)) {
            Map<String, Object> outline = readJson(outlineFile);
            Map<String, Object> book = new LinkedHashMap<>();
            String bookId = stringValue(outline.get("book_id"), stripSuffix(outlineFile.getFileName().toString(), "_outline.json"));
            if (seenBookIds.contains(bookId)) {
                continue;
            }
            seenBookIds.add(bookId);
            book.put("bookId", bookId);
            book.put("bookTitle", outline.getOrDefault("book_title", bookId));
            book.put("genre", outline.getOrDefault("genre", ""));
            book.put("targetWordCount", outline.getOrDefault("target_word_count", 0));
            book.put("totalVolumes", outline.getOrDefault("total_volumes", 0));
            book.put("totalChapters", outline.getOrDefault("total_chapters", 0));
            book.put("outlinePath", projectRoot(projectId).relativize(outlineFile).toString().replace("\\", "/"));
            book.put("projectSoulPath", "novel/soul/project_soul.md");
            book.put("updatedAt", modifiedAt(outlineFile));
            books.add(book);
        }

        books.sort(Comparator.comparing(book -> String.valueOf(book.get("updatedAt")), Comparator.reverseOrder()));
        return books;
    }

    public Map<String, Object> getOutline(String projectId, String bookId) {
        projectService.getProject(projectId);
        Path outlineFile = resolveOutlineFile(projectId, bookId);
        Map<String, Object> outline = camelizeMap(readJson(outlineFile));
        outline.put("outlinePath", projectRoot(projectId).relativize(outlineFile).toString().replace("\\", "/"));

        Path soulFile = projectRoot(projectId).resolve("novel").resolve("soul").resolve("project_soul.md");
        if (Files.exists(soulFile)) {
            outline.put("projectSoulPath", projectRoot(projectId).relativize(soulFile).toString().replace("\\", "/"));
            outline.put("projectSoul", readText(soulFile));
        }
        outline.put(
            "projectSoulGovernance",
            readSoulGovernance(projectId, stringValue(outline.get("bookId"), bookId))
        );
        outline.put(
            "outlineGovernance",
            readOutlineGovernance(projectId, stringValue(outline.get("bookId"), bookId))
        );

        Object volumesObject = outline.get("volumes");
        if (volumesObject instanceof List<?> volumes) {
            for (Object volumeObject : volumes) {
                if (volumeObject instanceof Map<?, ?> rawVolume) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> volume = (Map<String, Object>) rawVolume;
                    Object chaptersObject = volume.get("chapters");
                    volume.put("chapterCount", chaptersObject instanceof List<?> chapters ? chapters.size() : 0);
                }
            }
        }

        return outline;
    }

    public Map<String, Object> getProjectSoul(String projectId, String bookId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path soulFile = projectRoot(projectId).resolve("novel").resolve("soul").resolve("project_soul.md");
        if (!Files.exists(soulFile)) {
            throw new ResourceNotFoundException("Project Soul不存在");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bookId", resolvedBookId);
        response.put("path", projectRoot(projectId).relativize(soulFile).toString().replace("\\", "/"));
        response.put("content", readText(soulFile));
        response.put("updatedAt", modifiedAt(soulFile));
        response.put("governance", readSoulGovernance(projectId, resolvedBookId));
        return response;
    }

    public List<Map<String, Object>> listProjectSoulVersions(String projectId, String bookId) {
        projectService.getProject(projectId);
        resolveBookId(projectId, bookId);
        Path versionsDir = projectRoot(projectId).resolve("novel").resolve("soul").resolve("versions");
        if (!Files.exists(versionsDir)) {
            return List.of();
        }

        try (var stream = Files.list(versionsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("project_soul_.*\\.md"))
                .sorted(Comparator.comparing(this::modifiedAt).reversed())
                .map(path -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", stripSuffix(path.getFileName().toString(), ".md"));
                    item.put("path", relative(projectId, path));
                    item.put("sizeBytes", fileSize(path));
                    item.put("archivedAt", modifiedAt(path));
                    return item;
                })
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read Project Soul versions", e);
        }
    }

    public Map<String, Object> getProjectSoulVersion(String projectId, String bookId, String versionId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path versionFile = resolveProjectSoulVersionFile(projectId, versionId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bookId", resolvedBookId);
        response.put("id", stripSuffix(versionFile.getFileName().toString(), ".md"));
        response.put("path", relative(projectId, versionFile));
        response.put("content", readText(versionFile));
        response.put("sizeBytes", fileSize(versionFile));
        response.put("archivedAt", modifiedAt(versionFile));
        return response;
    }

    public Map<String, Object> restoreProjectSoulVersion(
            String projectId,
            String bookId,
            String versionId,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Map<String, Object> options = request == null ? Map.of() : request;
        Map<String, Object> governance = readSoulGovernance(projectId, resolvedBookId);
        if (booleanValue(governance.get("locked"), false)
                && !booleanOption(options, "overrideSoulLock", false)) {
            throw new IllegalArgumentException("Project Soul is locked; unlock it before restoring a version.");
        }

        Path versionFile = resolveProjectSoulVersionFile(projectId, versionId);
        Path soulPath = projectRoot(projectId).resolve("novel").resolve("soul").resolve("project_soul.md");
        Path previousSnapshotPath = null;
        if (Files.exists(soulPath) && booleanOption(options, "createVersionSnapshot", true)) {
            previousSnapshotPath = archiveTextSnapshot(
                projectId,
                soulPath,
                projectRoot(projectId).resolve("novel").resolve("soul").resolve("versions"),
                "project_soul",
                "md"
            );
        }

        String actor = stringValue(valueOf(options, "actor", "restoredBy"), "human");
        String note = stringValue(valueOf(options, "note", "reason"), "");
        String restoredAt = LocalDateTime.now().toString();
        writeText(soulPath, readText(versionFile));

        governance.put("bookId", resolvedBookId);
        governance.put("approvalStatus", "pending_review");
        governance.put("restoredBy", actor);
        governance.put("restoredAt", restoredAt);
        governance.put("restoreNote", note);
        governance.put("restoredFromVersionId", stripSuffix(versionFile.getFileName().toString(), ".md"));
        governance.put("restoredFromPath", relative(projectId, versionFile));
        governance.put("previousSnapshotPath", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : null);
        governance.put("updatedAt", restoredAt);
        writeJson(soulGovernanceFile(projectId), decamelizeMap(governance));

        Map<String, Object> response = getProjectSoul(projectId, resolvedBookId);
        response.put("status", "restored");
        response.put("restoredFromVersionId", stripSuffix(versionFile.getFileName().toString(), ".md"));
        response.put("restoredFromPath", relative(projectId, versionFile));
        response.put("previousSnapshotPath", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : null);
        response.put("restoredAt", restoredAt);
        return response;
    }

    public Map<String, Object> updateProjectSoulGovernance(
            String projectId,
            String bookId,
            String action,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Map<String, Object> options = request == null ? Map.of() : request;
        Map<String, Object> governance = readSoulGovernance(projectId, resolvedBookId);
        String actor = stringValue(valueOf(options, "actor", "reviewer"), "human");
        String note = stringValue(valueOf(options, "note", "reason"), "");
        String now = LocalDateTime.now().toString();

        switch (action) {
            case "lock" -> {
                governance.put("locked", true);
                governance.put("lockedBy", actor);
                governance.put("lockedAt", now);
                governance.put("lockNote", note);
            }
            case "unlock" -> {
                governance.put("locked", false);
                governance.put("unlockedBy", actor);
                governance.put("unlockedAt", now);
                governance.put("unlockNote", note);
            }
            case "approve" -> {
                governance.put("approvalStatus", "approved");
                governance.put("approvedBy", actor);
                governance.put("approvedAt", now);
                governance.put("approvalNote", note);
                if (booleanOption(options, "lock", true)) {
                    governance.put("locked", true);
                    governance.put("lockedBy", actor);
                    governance.put("lockedAt", now);
                    governance.put("lockNote", note);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported Project Soul governance action: " + action);
        }
        governance.put("bookId", resolvedBookId);
        governance.put("updatedAt", now);
        writeJson(soulGovernanceFile(projectId), decamelizeMap(governance));
        return getProjectSoul(projectId, resolvedBookId);
    }

    public Map<String, Object> updateOutlineGovernance(
            String projectId,
            String bookId,
            String action,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Map<String, Object> options = request == null ? Map.of() : request;
        Map<String, Object> governance = readOutlineGovernance(projectId, resolvedBookId);
        String actor = stringValue(valueOf(options, "actor", "reviewer"), "human");
        String note = stringValue(valueOf(options, "note", "reason"), "");
        String now = LocalDateTime.now().toString();

        switch (action) {
            case "lock" -> {
                governance.put("locked", true);
                governance.put("lockedBy", actor);
                governance.put("lockedAt", now);
                governance.put("lockNote", note);
            }
            case "unlock" -> {
                governance.put("locked", false);
                governance.put("unlockedBy", actor);
                governance.put("unlockedAt", now);
                governance.put("unlockNote", note);
            }
            case "approve" -> {
                governance.put("approvalStatus", "approved");
                governance.put("approvedBy", actor);
                governance.put("approvedAt", now);
                governance.put("approvalNote", note);
                if (booleanOption(options, "lock", true)) {
                    governance.put("locked", true);
                    governance.put("lockedBy", actor);
                    governance.put("lockedAt", now);
                    governance.put("lockNote", note);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported outline governance action: " + action);
        }
        governance.put("bookId", resolvedBookId);
        governance.put("updatedAt", now);
        writeJson(outlineGovernanceFile(projectId, resolvedBookId), decamelizeMap(governance));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bookId", resolvedBookId);
        response.put("governance", readOutlineGovernance(projectId, resolvedBookId));
        response.put("outline", getOutline(projectId, resolvedBookId));
        return response;
    }

    public Map<String, Object> updateOutline(
            String projectId,
            String bookId,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path outlineFile = resolveOutlineFile(projectId, resolvedBookId);
        Map<String, Object> before = readJson(outlineFile);
        Map<String, Object> options = request == null ? Map.of() : request;
        Map<String, Object> outlineGovernance = readOutlineGovernance(projectId, resolvedBookId);
        if (booleanValue(outlineGovernance.get("locked"), false)
                && !booleanOption(options, "overrideOutlineLock", false)) {
            throw new IllegalArgumentException("Outline is locked; unlock it before editing.");
        }

        Map<String, Object> updatedOutline = parseOutlinePayload(options);
        if (updatedOutline.isEmpty()) {
            updatedOutline = new LinkedHashMap<>(before);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedOutline = (Map<String, Object>) decamelize(updatedOutline);
        removeReadOnlyOutlineFields(normalizedOutline);
        normalizedOutline.put("book_id", resolvedBookId);
        normalizedOutline.put("project_id", projectId);
        if (before.containsKey("created_at")) {
            normalizedOutline.putIfAbsent("created_at", before.get("created_at"));
        }

        String editedAt = LocalDateTime.now().toString();
        String editor = stringValue(valueOf(options, "editor", "editor"), "human");
        String editNote = stringValue(valueOf(options, "edit_note", "editNote"), "");
        normalizedOutline.put("updated_at", editedAt);
        normalizedOutline.put("edited_at", editedAt);
        normalizedOutline.put("edited_by", editor);
        normalizedOutline.put("edit_note", editNote);

        Path snapshotPath = null;
        if (booleanOption(options, "createVersionSnapshot", true)) {
            snapshotPath = archiveOutlineSnapshot(
                projectId,
                resolvedBookId,
                outlineFile,
                before,
                "before_outline_edit"
            );
            normalizedOutline.put("previous_snapshot_path", relative(projectId, snapshotPath));
        }

        Object projectSoulValue = valueOf(options, "project_soul", "projectSoul");
        Path soulPath = projectRoot(projectId).resolve("novel").resolve("soul").resolve("project_soul.md");
        Path soulSnapshotPath = null;
        if (projectSoulValue != null) {
            Map<String, Object> governance = readSoulGovernance(projectId, resolvedBookId);
            if (booleanValue(governance.get("locked"), false)
                    && !booleanOption(options, "overrideSoulLock", false)) {
                throw new IllegalArgumentException("Project Soul is locked; unlock it before editing.");
            }
            if (Files.exists(soulPath) && booleanOption(options, "createVersionSnapshot", true)) {
                soulSnapshotPath = archiveTextSnapshot(
                    projectId,
                    soulPath,
                    projectRoot(projectId).resolve("novel").resolve("soul").resolve("versions"),
                    "project_soul",
                    "md"
                );
            }
            writeText(soulPath, String.valueOf(projectSoulValue));
            governance.put("approvalStatus", "pending_review");
            governance.put("lastEditedBy", editor);
            governance.put("lastEditedAt", editedAt);
            governance.put("lastEditNote", editNote);
            governance.put("updatedAt", editedAt);
            governance.put("bookId", resolvedBookId);
            writeJson(soulGovernanceFile(projectId), decamelizeMap(governance));
        }

        writeJson(outlineFile, normalizedOutline);
        outlineGovernance.put("approvalStatus", "pending_review");
        outlineGovernance.put("lastEditedBy", editor);
        outlineGovernance.put("lastEditedAt", editedAt);
        outlineGovernance.put("lastEditNote", editNote);
        outlineGovernance.put("bookId", resolvedBookId);
        outlineGovernance.put("updatedAt", editedAt);
        writeJson(outlineGovernanceFile(projectId, resolvedBookId), decamelizeMap(outlineGovernance));

        Path reportPath = writeOutlineEditReport(
            projectId,
            resolvedBookId,
            outlineFile,
            before,
            normalizedOutline,
            snapshotPath,
            soulSnapshotPath,
            editor,
            editNote
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("outline", getOutline(projectId, resolvedBookId));
        response.put("outlineDb", outlineArtifactService.syncOutlineFromWorkspace(projectId, resolvedBookId));
        response.put("updatedPath", relative(projectId, outlineFile));
        response.put("snapshotPath", snapshotPath != null ? relative(projectId, snapshotPath) : null);
        response.put("soulPath", projectSoulValue != null ? relative(projectId, soulPath) : null);
        response.put("soulSnapshotPath", soulSnapshotPath != null ? relative(projectId, soulSnapshotPath) : null);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("editedAt", editedAt);
        return response;
    }

    public List<Map<String, Object>> listOutlineReviews(String projectId, String bookId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(resolvedBookId).resolve("outline");
        if (!Files.exists(reviewsDir)) {
            return List.of();
        }

        try (var stream = Files.list(reviewsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("outline_.*\\.json"))
                .sorted(Comparator.comparing(this::modifiedAt).reversed())
                .map(path -> {
                    Map<String, Object> review = camelizeMap(readJson(path));
                    review.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    review.put("path", relative(projectId, path));
                    review.put("updatedAt", modifiedAt(path));
                    return review;
                })
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取大纲审查目录失败", e);
        }
    }

    public List<Map<String, Object>> listChapters(String projectId, String bookId) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Map<String, Map<String, Object>> chaptersByKey = new LinkedHashMap<>();

        for (Path chapterFile : chapterFiles(projectId, resolvedBookId)) {
            Map<String, Object> chapter = chapterResponse(projectId, chapterFile, false);
            String key = intValue(chapter.get("volumeNumber")) + ":" + intValue(chapter.get("chapterNumber"));
            Map<String, Object> existing = chaptersByKey.get(key);
            if (existing == null || Boolean.TRUE.equals(chapter.get("isFinal"))) {
                chaptersByKey.put(key, chapter);
            }
        }

        List<Map<String, Object>> chapters = new ArrayList<>(chaptersByKey.values());
        chapters.sort(Comparator
            .comparing((Map<String, Object> chapter) -> intValue(chapter.get("volumeNumber")))
            .thenComparing(chapter -> intValue(chapter.get("chapterNumber"))));
        return chapters;
    }

    public Map<String, Object> getChapter(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path chapterFile = resolveChapterFile(projectId, resolvedBookId, volumeNumber, chapterNumber);
        return chapterResponse(projectId, chapterFile, true);
    }

    public Task reviseChapter(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }

        Map<String, Object> options = request == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("project_id", projectId);
        parameters.put("book_id", resolvedBookId);
        parameters.put("volume_number", volumeNumber);
        parameters.put("chapter_number", chapterNumber);
        parameters.put("source_stage", stringValue(valueOf(options, "source_stage", "sourceStage"), "draft"));
        parameters.put("user_instruction", stringValue(valueOf(options, "user_instruction", "userInstruction"), ""));
        parameters.put("issues", options.getOrDefault("issues", List.of()));
        parameters.put("include_boundary_warnings", booleanOption(options, "includeBoundaryWarnings", true));
        parameters.put("use_project_skills", booleanOption(options, "useProjectSkills", true));
        parameters.put("max_iterations", intOption(options, "maxIterations", 1));
        parameters.put("create_version_snapshot", booleanOption(options, "createVersionSnapshot", true));

        Task task = taskExecutorService.createTask(
            projectId,
            "chapter_revision",
            "chapter_revision",
            Map.of(
                "book_id", resolvedBookId,
                "volume_number", volumeNumber,
                "chapter_number", chapterNumber
            ),
            parameters
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return task;
    }

    public Map<String, Object> finalizeChapter(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }

        Map<String, Object> options = request == null ? Map.of() : request;
        boolean overwrite = booleanOption(options, "overwrite", true);
        boolean triggerMemoryExtraction = booleanOption(options, "triggerMemoryExtraction", true);
        boolean createVersionSnapshot = booleanOption(options, "createVersionSnapshot", true);
        String finalizer = stringValue(valueOf(options, "finalizer", "editor"), "system");
        String finalizeNote = stringValue(valueOf(options, "finalize_note", "finalizeNote"), "");

        Path draftFile = draftChapterFile(projectId, resolvedBookId, volumeNumber, chapterNumber);
        Path finalFile = finalChapterFile(projectId, resolvedBookId, volumeNumber, chapterNumber);
        Path sourceFile;
        if (Files.exists(draftFile)) {
            sourceFile = draftFile;
        } else if (Files.exists(finalFile)) {
            sourceFile = finalFile;
        } else {
            throw new ResourceNotFoundException("可发布的章节草稿不存在: " + resolvedBookId + "/" + volumeNumber + "/" + chapterNumber);
        }
        if (Files.exists(finalFile) && !overwrite && !sourceFile.equals(finalFile)) {
            throw new IllegalArgumentException("终稿已存在，如需覆盖请传 overwrite=true");
        }

        Map<String, Object> chapter = readJson(sourceFile);
        Map<String, Object> previousFinal = Files.exists(finalFile) ? readJson(finalFile) : null;
        Path previousFinalSnapshotPath = null;
        if (previousFinal != null && createVersionSnapshot) {
            previousFinalSnapshotPath = archiveChapterSnapshot(
                projectId,
                resolvedBookId,
                volumeNumber,
                chapterNumber,
                finalFile,
                previousFinal,
                "before_finalize_overwrite",
                null
            );
        }

        int sourceVersion = intValue(valueOf(chapter, "version", "version"));
        int previousFinalVersion = previousFinal == null ? 0 : intValue(valueOf(previousFinal, "version", "version"));
        int finalVersion = previousFinalVersion > 0
            ? Math.max(previousFinalVersion + 1, sourceVersion)
            : Math.max(sourceVersion, 1);
        String finalizedAt = LocalDateTime.now().toString();
        chapter.put("status", "finalized");
        chapter.put("stage", "final");
        chapter.put("review_status", "approved");
        chapter.put("version", finalVersion);
        chapter.put("finalized_at", finalizedAt);
        chapter.put("updated_at", finalizedAt);
        chapter.put("final_path", relative(projectId, finalFile));
        chapter.put("previous_final_snapshot_path", previousFinalSnapshotPath != null ? relative(projectId, previousFinalSnapshotPath) : null);
        chapter.put("finalizer", finalizer);
        chapter.put("finalize_note", finalizeNote);
        if (Files.exists(draftFile)) {
            chapter.put("source_draft_path", relative(projectId, draftFile));
        }

        writeJson(finalFile, chapter);
        writeChapterText(finalFile, chapter);

        Task memoryTask = null;
        if (triggerMemoryExtraction) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("project_id", projectId);
            parameters.put("book_id", resolvedBookId);
            parameters.put("chapter_id", chapterId(chapter, finalFile));
            parameters.put("extract_characters", true);
            parameters.put("extract_world_settings", true);
            parameters.put("extract_plot", true);
            parameters.put("extract_suspense", true);
            parameters.put("extract_timeline", true);
            parameters.put("update_existing", true);

            memoryTask = taskExecutorService.createTask(
                projectId,
                "memory_extraction",
                "memory_extraction",
                Map.of(),
                parameters
            );
            taskExecutorService.executeTaskAsync(memoryTask.getId());
        }

        Path reportPath = writeFinalizeReport(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            sourceFile,
            finalFile,
            previousFinalSnapshotPath,
            previousFinal,
            chapter,
            overwrite,
            createVersionSnapshot,
            finalizer,
            finalizeNote,
            memoryTask
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", chapterResponse(projectId, finalFile, true));
        response.put("chapterDb", chapterArtifactService.syncChapterFromWorkspace(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            "final"
        ));
        response.put("finalPath", relative(projectId, finalFile));
        response.put("sourceDraftPath", Files.exists(draftFile) ? relative(projectId, draftFile) : null);
        response.put("previousFinalSnapshotPath", previousFinalSnapshotPath != null ? relative(projectId, previousFinalSnapshotPath) : null);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("versionBefore", previousFinalVersion > 0 ? previousFinalVersion : null);
        response.put("versionAfter", finalVersion);
        response.put("memoryTaskId", memoryTask != null ? memoryTask.getId() : null);
        response.put("memoryTaskStatus", memoryTask != null ? memoryTask.getStatus().name() : null);
        response.put("finalizedAt", finalizedAt);
        return response;
    }

    public List<Map<String, Object>> listChapterVersions(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path versionsDir = projectRoot(projectId).resolve("novel").resolve("chapters").resolve("versions")
            .resolve(resolvedBookId).resolve("volume_" + volumeNumber);
        if (!Files.exists(versionsDir)) {
            return List.of();
        }

        try (var stream = Files.list(versionsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("chapter_" + chapterNumber + "_v.*\\.json"))
                .sorted(Comparator.comparing(this::modifiedAt).reversed())
                .map(path -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    Map<String, Object> chapter = camelizeMap(readJson(path));
                    item.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    item.put("path", relative(projectId, path));
                    item.put("version", chapter.getOrDefault("version", 0));
                    item.put("chapterId", chapter.getOrDefault("chapterId", ""));
                    item.put("chapterTitle", chapter.getOrDefault("chapterTitle", ""));
                    item.put("wordCount", chapter.getOrDefault("wordCount", 0));
                    item.put("reviewStatus", chapter.getOrDefault("reviewStatus", ""));
                    item.put("createdAt", chapter.getOrDefault("createdAt", modifiedAt(path)));
                    item.put("archivedAt", chapter.getOrDefault("archivedAt", modifiedAt(path)));
                    item.put("archiveReason", chapter.getOrDefault("archiveReason", ""));
                    item.put("sourcePath", chapter.getOrDefault("sourcePath", ""));
                    item.put("restoreFromVersionId", chapter.getOrDefault("restoreFromVersionId", ""));
                    return item;
                })
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取章节版本目录失败", e);
        }
    }

    public List<Map<String, Object>> listChapterReviews(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(resolvedBookId).resolve("volume_" + volumeNumber);
        if (!Files.exists(reviewsDir)) {
            return List.of();
        }

        try (var stream = Files.list(reviewsDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().matches("chapter_" + chapterNumber + "_.*\\.json"))
                .sorted(Comparator.comparing(this::modifiedAt).reversed())
                .map(path -> {
                    Map<String, Object> review = camelizeMap(readJson(path));
                    review.put("id", stripSuffix(path.getFileName().toString(), ".json"));
                    review.put("path", relative(projectId, path));
                    review.put("updatedAt", modifiedAt(path));
                    return review;
                })
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取章节审查目录失败", e);
        }
    }

    public Map<String, Object> restoreChapterVersion(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            String versionId,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }
        validateId(versionId, "versionId");

        Map<String, Object> options = request == null ? Map.of() : request;
        Path versionFile = resolveChapterVersionFile(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            versionId
        );
        Map<String, Object> versionSnapshot = readJson(versionFile);
        validateVersionSnapshot(versionSnapshot, resolvedBookId, volumeNumber, chapterNumber);

        String targetStage = resolveRestoreTargetStage(options, versionSnapshot);
        Path targetFile = "final".equals(targetStage)
            ? finalChapterFile(projectId, resolvedBookId, volumeNumber, chapterNumber)
            : draftChapterFile(projectId, resolvedBookId, volumeNumber, chapterNumber);

        Path previousSnapshotPath = null;
        Map<String, Object> currentChapter = null;
        if (Files.exists(targetFile)) {
            currentChapter = readJson(targetFile);
            if (booleanOption(options, "createVersionSnapshot", true)) {
                previousSnapshotPath = archiveChapterSnapshot(
                    projectId,
                    resolvedBookId,
                    volumeNumber,
                    chapterNumber,
                    targetFile,
                    currentChapter,
                    "before_restore",
                    versionId
                );
            }
        }

        Map<String, Object> restoredChapter = new LinkedHashMap<>(versionSnapshot);
        int selectedVersion = intValue(valueOf(restoredChapter, "version", "version"));
        int currentVersion = currentChapter == null ? 0 : intValue(valueOf(currentChapter, "version", "version"));
        int restoredVersion = currentVersion > 0 ? Math.max(currentVersion + 1, selectedVersion + 1) : selectedVersion;
        String restoredAt = LocalDateTime.now().toString();

        restoredChapter.remove("archived_at");
        restoredChapter.remove("archivedAt");
        restoredChapter.remove("source_path");
        restoredChapter.remove("sourcePath");
        restoredChapter.remove("archive_reason");
        restoredChapter.remove("archiveReason");
        restoredChapter.put("book_id", resolvedBookId);
        restoredChapter.put("volume_number", volumeNumber);
        restoredChapter.put("chapter_number", chapterNumber);
        restoredChapter.put("version", restoredVersion);
        restoredChapter.put("status", "final".equals(targetStage) ? "finalized" : "completed");
        restoredChapter.put("stage", targetStage);
        restoredChapter.put("review_status", "restored");
        restoredChapter.put("updated_at", restoredAt);
        restoredChapter.put("restored_at", restoredAt);
        restoredChapter.put("restored_from_version_id", versionId);
        restoredChapter.put("restored_from_path", relative(projectId, versionFile));
        restoredChapter.put("restore_target_stage", targetStage);
        restoredChapter.put("previous_snapshot_path", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : null);
        if ("final".equals(targetStage)) {
            restoredChapter.put("final_path", relative(projectId, targetFile));
            restoredChapter.put("finalized_at", restoredAt);
        }

        writeJson(targetFile, restoredChapter);
        writeChapterText(targetFile, restoredChapter);

        Path reportPath = writeRestoreReport(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            targetStage,
            versionId,
            versionFile,
            targetFile,
            previousSnapshotPath,
            currentChapter,
            restoredChapter
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", chapterResponse(projectId, targetFile, true));
        response.put("chapterDb", chapterArtifactService.syncChapterFromWorkspace(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            targetStage
        ));
        response.put("restoredPath", relative(projectId, targetFile));
        response.put("restoredFromVersionId", versionId);
        response.put("restoredFromPath", relative(projectId, versionFile));
        response.put("previousSnapshotPath", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : null);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("targetStage", targetStage);
        response.put("restoredAt", restoredAt);
        return response;
    }

    public Map<String, Object> diffChapter(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }

        Map<String, Object> options = request == null ? Map.of() : request;
        ChapterComparison from = resolveChapterComparison(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            options,
            "from",
            "draft"
        );
        ChapterComparison to = resolveChapterComparison(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            options,
            "to",
            "final"
        );

        String fromTitle = stringValue(valueOf(from.chapter(), "chapter_title", "chapterTitle"), "");
        String toTitle = stringValue(valueOf(to.chapter(), "chapter_title", "chapterTitle"), "");
        String fromContent = stringValue(valueOf(from.chapter(), "content", "content"), "");
        String toContent = stringValue(valueOf(to.chapter(), "content", "content"), "");
        int fromWordCount = chapterWordCount(from.chapter(), fromContent);
        int toWordCount = chapterWordCount(to.chapter(), toContent);
        List<Map<String, Object>> hunks = chapterLineDiff(contentLines(fromContent), contentLines(toContent));

        long addedLines = hunks.stream().filter(item -> "added".equals(item.get("type"))).count();
        long removedLines = hunks.stream().filter(item -> "removed".equals(item.get("type"))).count();
        long changedLines = hunks.stream().filter(item -> "changed".equals(item.get("type"))).count();
        long unchangedLines = hunks.stream().filter(item -> "equal".equals(item.get("type"))).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("titleChanged", !fromTitle.equals(toTitle));
        summary.put("contentChanged", !fromContent.equals(toContent));
        summary.put("wordCountDelta", toWordCount - fromWordCount);
        summary.put("fromWordCount", fromWordCount);
        summary.put("toWordCount", toWordCount);
        summary.put("addedLines", addedLines);
        summary.put("removedLines", removedLines);
        summary.put("changedLines", changedLines);
        summary.put("unchangedLines", unchangedLines);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", projectId);
        response.put("bookId", resolvedBookId);
        response.put("volumeNumber", volumeNumber);
        response.put("chapterNumber", chapterNumber);
        response.put("from", chapterComparisonDescriptor(projectId, from, fromContent));
        response.put("to", chapterComparisonDescriptor(projectId, to, toContent));
        response.put("summary", summary);
        response.put("hunks", hunks);
        response.put("generatedAt", LocalDateTime.now().toString());
        return response;
    }

    public Map<String, Object> reviewChapter(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }

        Map<String, Object> options = request == null ? Map.of() : request;
        String decision = normalizeReviewDecision(stringValue(
            valueOf(options, "decision", "decision"),
            stringValue(valueOf(options, "review_status", "reviewStatus"), "approved")
        ));
        String sourceStage = stringValue(valueOf(options, "source_stage", "sourceStage"), "auto").toLowerCase();
        Path chapterFile = resolveChapterFileByStage(projectId, resolvedBookId, volumeNumber, chapterNumber, sourceStage);
        Map<String, Object> chapter = readJson(chapterFile);

        String reviewedAt = LocalDateTime.now().toString();
        String reviewer = stringValue(
            valueOf(options, "reviewer", "reviewer"),
            stringValue(valueOf(options, "reviewed_by", "reviewedBy"), "human")
        );
        String feedback = stringValue(valueOf(options, "feedback", "feedback"), "");
        String note = stringValue(valueOf(options, "note", "note"), "");

        Map<String, Object> decisionRecord = new LinkedHashMap<>();
        decisionRecord.put("review_type", "chapter_human_review");
        decisionRecord.put("decision", decision);
        decisionRecord.put("reviewer", reviewer);
        decisionRecord.put("feedback", feedback);
        decisionRecord.put("note", note);
        decisionRecord.put("reviewed_at", reviewedAt);
        decisionRecord.put("source_stage", isFinalChapterPath(projectId, chapterFile) ? "final" : "draft");
        decisionRecord.put("version", valueOf(chapter, "version", "version"));
        decisionRecord.put("word_count", valueOf(chapter, "word_count", "wordCount"));

        chapter.put("review_status", decision);
        chapter.put("human_review_status", decision);
        chapter.put("human_reviewer", reviewer);
        chapter.put("human_feedback", feedback);
        chapter.put("human_reviewed_at", reviewedAt);
        chapter.put("updated_at", reviewedAt);
        chapter.put("needs_revision", "needs_revision".equals(decision));
        appendHistory(chapter, "human_review_history", decisionRecord);

        writeJson(chapterFile, chapter);
        writeChapterText(chapterFile, chapter);

        Path reportPath = writeHumanReviewReport(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            chapterFile,
            chapter,
            decisionRecord
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", chapterResponse(projectId, chapterFile, true));
        response.put("chapterDb", chapterArtifactService.syncChapterFromWorkspace(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            isFinalChapterPath(projectId, chapterFile) ? "final" : "draft"
        ));
        response.put("decision", decision);
        response.put("reviewer", reviewer);
        response.put("feedback", feedback);
        response.put("reviewedAt", reviewedAt);
        response.put("reviewPath", relative(projectId, reportPath));
        return response;
    }

    public Map<String, Object> updateChapter(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Map<String, Object> request) {
        projectService.getProject(projectId);
        String resolvedBookId = resolveBookId(projectId, bookId);
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }

        Map<String, Object> options = request == null ? Map.of() : request;
        String sourceStage = stringValue(valueOf(options, "source_stage", "sourceStage"), "draft").toLowerCase();
        Path chapterFile = resolveChapterFileByStage(projectId, resolvedBookId, volumeNumber, chapterNumber, sourceStage);
        Map<String, Object> before = readJson(chapterFile);
        Map<String, Object> chapter = new LinkedHashMap<>(before);

        Path snapshotPath = null;
        if (booleanOption(options, "createVersionSnapshot", true)) {
            snapshotPath = archiveChapterSnapshot(
                projectId,
                resolvedBookId,
                volumeNumber,
                chapterNumber,
                chapterFile,
                before,
                "before_manual_edit",
                null
            );
        }

        String previousContent = stringValue(valueOf(before, "content", "content"), "");
        String previousTitle = stringValue(valueOf(before, "chapter_title", "chapterTitle"), "");
        boolean contentChanged = false;
        boolean titleChanged = false;

        Object titleValue = valueOf(options, "chapter_title", "chapterTitle");
        if (titleValue != null) {
            String title = String.valueOf(titleValue);
            chapter.put("chapter_title", title);
            titleChanged = !title.equals(previousTitle);
        }

        Object contentValue = valueOf(options, "content", "content");
        if (contentValue != null) {
            String content = String.valueOf(contentValue);
            chapter.put("content", content);
            chapter.put("word_count", content.length());
            contentChanged = !content.equals(previousContent);
        }

        String editNote = stringValue(valueOf(options, "edit_note", "editNote"), "");
        String editor = stringValue(valueOf(options, "editor", "editor"), "human");
        String editedAt = LocalDateTime.now().toString();
        int previousVersion = intValue(valueOf(before, "version", "version"));
        int nextVersion = previousVersion + (booleanOption(options, "incrementVersion", true) ? 1 : 0);
        chapter.put("version", nextVersion);
        chapter.put("status", isFinalChapterPath(projectId, chapterFile) ? "finalized" : "completed");
        chapter.put("stage", isFinalChapterPath(projectId, chapterFile) ? "final" : "draft");
        chapter.put("review_status", "manual_edited");
        chapter.put("human_review_status", "manual_edited");
        chapter.put("needs_revision", false);
        chapter.put("updated_at", editedAt);
        chapter.put("edited_at", editedAt);
        chapter.put("edited_by", editor);
        chapter.put("edit_note", editNote);
        if (snapshotPath != null) {
            chapter.put("previous_snapshot_path", relative(projectId, snapshotPath));
        }

        Map<String, Object> editRecord = new LinkedHashMap<>();
        editRecord.put("edit_type", "manual_edit");
        editRecord.put("editor", editor);
        editRecord.put("edit_note", editNote);
        editRecord.put("edited_at", editedAt);
        editRecord.put("source_stage", isFinalChapterPath(projectId, chapterFile) ? "final" : "draft");
        editRecord.put("content_changed", contentChanged);
        editRecord.put("title_changed", titleChanged);
        editRecord.put("version_before", previousVersion);
        editRecord.put("version_after", nextVersion);
        editRecord.put("word_count_before", previousContent.length());
        editRecord.put("word_count_after", intValue(valueOf(chapter, "word_count", "wordCount")));
        editRecord.put("snapshot_path", snapshotPath != null ? relative(projectId, snapshotPath) : null);
        appendHistory(chapter, "manual_edit_history", editRecord);

        writeJson(chapterFile, chapter);
        writeChapterText(chapterFile, chapter);
        Path reportPath = writeManualEditReport(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            chapterFile,
            before,
            chapter,
            editRecord
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", chapterResponse(projectId, chapterFile, true));
        response.put("chapterDb", chapterArtifactService.syncChapterFromWorkspace(
            projectId,
            resolvedBookId,
            volumeNumber,
            chapterNumber,
            isFinalChapterPath(projectId, chapterFile) ? "final" : "draft"
        ));
        response.put("updatedPath", relative(projectId, chapterFile));
        response.put("snapshotPath", snapshotPath != null ? relative(projectId, snapshotPath) : null);
        response.put("reportPath", relative(projectId, reportPath));
        response.put("versionBefore", previousVersion);
        response.put("versionAfter", nextVersion);
        response.put("editedAt", editedAt);
        return response;
    }

    private Map<String, Object> chapterResponse(String projectId, Path chapterFile, boolean includeContent) {
        Map<String, Object> chapter = camelizeMap(readJson(chapterFile));
        chapter.put("id", chapter.getOrDefault("chapterId", stripSuffix(chapterFile.getFileName().toString(), ".json")));
        boolean isFinal = isFinalChapterPath(projectId, chapterFile);
        chapter.put("status", isFinal ? "finalized" : chapter.getOrDefault("status", "completed"));
        chapter.put("stage", isFinal ? "final" : chapter.getOrDefault("stage", "draft"));
        chapter.put("isFinal", isFinal);
        chapter.put("path", projectRoot(projectId).relativize(chapterFile).toString().replace("\\", "/"));
        chapter.put("createdAt", chapter.getOrDefault("createdAt", modifiedAt(chapterFile)));
        if (!includeContent) {
            chapter.remove("content");
            chapter.remove("reviewComments");
        }
        return chapter;
    }

    private String resolveBookId(String projectId, String requestedBookId) {
        if (requestedBookId != null && !requestedBookId.isBlank() && !"default".equals(requestedBookId)) {
            validateId(requestedBookId, "bookId");
            return requestedBookId;
        }

        Path outlineFile = latestOutlineFile(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("项目尚未生成大纲"));
        Map<String, Object> outline = readJson(outlineFile);
        return stringValue(outline.get("book_id"), stripSuffix(outlineFile.getFileName().toString(), "_outline.json"));
    }

    private Path resolveOutlineFile(String projectId, String bookId) {
        String resolvedBookId = resolveBookId(projectId, bookId);
        List<Path> candidates = List.of(
            projectRoot(projectId).resolve("novel").resolve("outline").resolve(resolvedBookId + "_outline.json"),
            projectRoot(projectId).resolve("outlines").resolve(resolvedBookId + "_outline.json")
        );

        return candidates.stream()
            .filter(Files::exists)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("大纲不存在: " + resolvedBookId));
    }

    private Path resolveChapterFile(String projectId, String bookId, Integer volumeNumber, Integer chapterNumber) {
        if (volumeNumber == null || volumeNumber < 1 || chapterNumber == null || chapterNumber < 1) {
            throw new IllegalArgumentException("卷号和章节号必须大于0");
        }

        List<Path> candidates = List.of(
            finalChapterFile(projectId, bookId, volumeNumber, chapterNumber),
            draftChapterFile(projectId, bookId, volumeNumber, chapterNumber),
            projectRoot(projectId).resolve("books").resolve(bookId).resolve("volume_" + volumeNumber)
                .resolve("chapters").resolve("chapter_" + chapterNumber + ".json")
        );

        return candidates.stream()
            .filter(Files::exists)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("章节不存在: " + bookId + "/" + volumeNumber + "/" + chapterNumber));
    }

    private Path resolveChapterFileByStage(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            String sourceStage) {
        String normalizedStage = sourceStage == null || sourceStage.isBlank() ? "auto" : sourceStage.toLowerCase();
        if ("auto".equals(normalizedStage)) {
            return resolveChapterFile(projectId, bookId, volumeNumber, chapterNumber);
        }
        if ("draft".equals(normalizedStage)) {
            Path draftFile = draftChapterFile(projectId, bookId, volumeNumber, chapterNumber);
            if (Files.exists(draftFile)) {
                return draftFile;
            }
        } else if ("final".equals(normalizedStage)) {
            Path finalFile = finalChapterFile(projectId, bookId, volumeNumber, chapterNumber);
            if (Files.exists(finalFile)) {
                return finalFile;
            }
        } else {
            throw new IllegalArgumentException("sourceStage 仅支持 draft/final/auto");
        }
        throw new ResourceNotFoundException("指定阶段章节不存在: " + bookId + "/" + volumeNumber + "/" + chapterNumber + "/" + normalizedStage);
    }

    private Path resolveProjectSoulVersionFile(String projectId, String versionId) {
        if (versionId == null || !versionId.matches("project_soul_[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Project Soul version id: " + versionId);
        }
        Path versionsDir = projectRoot(projectId).resolve("novel").resolve("soul").resolve("versions").normalize();
        Path versionFile = versionsDir.resolve(versionId + ".md").normalize();
        if (!versionFile.startsWith(versionsDir) || !Files.exists(versionFile) || !Files.isRegularFile(versionFile)) {
            throw new ResourceNotFoundException("Project Soul version does not exist: " + versionId);
        }
        return versionFile;
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

    private boolean isStandardOutlinePath(String projectId, Path path) {
        Path standardDir = projectRoot(projectId).resolve("novel").resolve("outline").normalize();
        return path.normalize().startsWith(standardDir);
    }

    private Optional<Path> latestOutlineFile(String projectId) {
        return outlineFiles(projectId).stream().findFirst();
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
        return files;
    }

    private Path draftChapterFile(String projectId, String bookId, Integer volumeNumber, Integer chapterNumber) {
        return projectRoot(projectId).resolve("novel").resolve("chapters").resolve("drafts")
            .resolve(bookId).resolve("volume_" + volumeNumber).resolve("chapter_" + chapterNumber + ".json");
    }

    private Path finalChapterFile(String projectId, String bookId, Integer volumeNumber, Integer chapterNumber) {
        return projectRoot(projectId).resolve("novel").resolve("chapters").resolve("final")
            .resolve(bookId).resolve("volume_" + volumeNumber).resolve("chapter_" + chapterNumber + ".json");
    }

    private Path resolveChapterVersionFile(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            String versionId) {
        Path versionsDir = projectRoot(projectId).resolve("novel").resolve("chapters").resolve("versions")
            .resolve(bookId).resolve("volume_" + volumeNumber).normalize();
        Path versionFile = versionsDir.resolve(versionId + ".json").normalize();
        if (!versionFile.startsWith(versionsDir) || !Files.exists(versionFile) || !Files.isRegularFile(versionFile)) {
            throw new ResourceNotFoundException("章节历史版本不存在: " + versionId);
        }
        if (!versionFile.getFileName().toString().matches("chapter_" + chapterNumber + "_v.*\\.json")) {
            throw new IllegalArgumentException("历史版本不属于当前章节: " + versionId);
        }
        return versionFile;
    }

    private void validateVersionSnapshot(
            Map<String, Object> snapshot,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber) {
        String snapshotBookId = stringValue(valueOf(snapshot, "book_id", "bookId"), bookId);
        int snapshotVolume = intValue(valueOf(snapshot, "volume_number", "volumeNumber"));
        int snapshotChapter = intValue(valueOf(snapshot, "chapter_number", "chapterNumber"));
        if (!bookId.equals(snapshotBookId)
            || (snapshotVolume > 0 && snapshotVolume != volumeNumber)
            || (snapshotChapter > 0 && snapshotChapter != chapterNumber)) {
            throw new IllegalArgumentException("历史版本元数据与当前章节不匹配");
        }
    }

    private String resolveRestoreTargetStage(Map<String, Object> options, Map<String, Object> snapshot) {
        String targetStage = stringValue(valueOf(options, "target_stage", "targetStage"), "draft").toLowerCase();
        if ("auto".equals(targetStage)) {
            String sourcePath = stringValue(valueOf(snapshot, "source_path", "sourcePath"), "");
            targetStage = sourcePath.startsWith("novel/chapters/final/") ? "final" : "draft";
        }
        if (!List.of("draft", "final").contains(targetStage)) {
            throw new IllegalArgumentException("targetStage 仅支持 draft/final/auto");
        }
        return targetStage;
    }

    private ChapterComparison resolveChapterComparison(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Map<String, Object> options,
            String side,
            String defaultStage) {
        Object versionIdValue = valueOf(options, side + "_version_id", side + "VersionId");
        if (versionIdValue != null && !String.valueOf(versionIdValue).isBlank()) {
            String versionId = String.valueOf(versionIdValue);
            validateId(versionId, side + "VersionId");
            Path versionFile = resolveChapterVersionFile(
                projectId,
                bookId,
                volumeNumber,
                chapterNumber,
                versionId
            );
            Map<String, Object> versionSnapshot = readJson(versionFile);
            validateVersionSnapshot(versionSnapshot, bookId, volumeNumber, chapterNumber);
            return new ChapterComparison(
                "version",
                inferChapterStage(projectId, versionFile, versionSnapshot),
                versionId,
                versionFile,
                versionSnapshot
            );
        }

        String stage = stringValue(valueOf(options, side + "_stage", side + "Stage"), defaultStage).toLowerCase();
        Path chapterFile = resolveChapterFileByStage(projectId, bookId, volumeNumber, chapterNumber, stage);
        Map<String, Object> chapter = readJson(chapterFile);
        return new ChapterComparison(
            "stage",
            inferChapterStage(projectId, chapterFile, chapter),
            null,
            chapterFile,
            chapter
        );
    }

    private String inferChapterStage(String projectId, Path path, Map<String, Object> chapter) {
        String stage = stringValue(valueOf(chapter, "stage", "stage"), "").toLowerCase();
        if ("draft".equals(stage) || "final".equals(stage)) {
            return stage;
        }
        String sourcePath = stringValue(valueOf(chapter, "source_path", "sourcePath"), "");
        if (sourcePath.startsWith("novel/chapters/final/")) {
            return "final";
        }
        if (sourcePath.startsWith("novel/chapters/drafts/")) {
            return "draft";
        }
        return isFinalChapterPath(projectId, path) ? "final" : "draft";
    }

    private Map<String, Object> chapterComparisonDescriptor(
            String projectId,
            ChapterComparison comparison,
            String content) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("kind", comparison.kind());
        descriptor.put("stage", comparison.stage());
        descriptor.put("versionId", comparison.versionId());
        descriptor.put("path", relative(projectId, comparison.path()));
        descriptor.put("chapterId", chapterId(comparison.chapter(), comparison.path()));
        descriptor.put("chapterTitle", valueOf(comparison.chapter(), "chapter_title", "chapterTitle"));
        descriptor.put("version", valueOf(comparison.chapter(), "version", "version"));
        descriptor.put("wordCount", chapterWordCount(comparison.chapter(), content));
        descriptor.put("lineCount", contentLines(content).size());
        descriptor.put("reviewStatus", valueOf(comparison.chapter(), "review_status", "reviewStatus"));
        descriptor.put("updatedAt", valueOf(comparison.chapter(), "updated_at", "updatedAt"));
        return descriptor;
    }

    private int chapterWordCount(Map<String, Object> chapter, String content) {
        int wordCount = intValue(valueOf(chapter, "word_count", "wordCount"));
        return wordCount > 0 || content.isEmpty() ? wordCount : content.length();
    }

    private List<String> contentLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return List.of(content.split("\\R", -1));
    }

    private List<Map<String, Object>> chapterLineDiff(List<String> left, List<String> right) {
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

        List<Map<String, Object>> raw = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            if (left.get(i).equals(right.get(j))) {
                raw.add(chapterDiffLine("equal", i + 1, j + 1, left.get(i), right.get(j)));
                i += 1;
                j += 1;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                raw.add(chapterDiffLine("removed", i + 1, null, left.get(i), null));
                i += 1;
            } else {
                raw.add(chapterDiffLine("added", null, j + 1, null, right.get(j)));
                j += 1;
            }
        }
        while (i < left.size()) {
            raw.add(chapterDiffLine("removed", i + 1, null, left.get(i), null));
            i += 1;
        }
        while (j < right.size()) {
            raw.add(chapterDiffLine("added", null, j + 1, null, right.get(j)));
            j += 1;
        }
        return collapseChangedChapterLines(raw);
    }

    private List<Map<String, Object>> collapseChangedChapterLines(List<Map<String, Object>> raw) {
        List<Map<String, Object>> collapsed = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> added = new ArrayList<>();
        for (Map<String, Object> line : raw) {
            String type = stringValue(line.get("type"), "");
            if ("removed".equals(type)) {
                removed.add(line);
            } else if ("added".equals(type)) {
                added.add(line);
            } else {
                flushChangedChapterBlock(collapsed, removed, added);
                collapsed.add(line);
            }
        }
        flushChangedChapterBlock(collapsed, removed, added);
        return collapsed;
    }

    private void flushChangedChapterBlock(
            List<Map<String, Object>> collapsed,
            List<Map<String, Object>> removed,
            List<Map<String, Object>> added) {
        int changedCount = Math.min(removed.size(), added.size());
        for (int i = 0; i < changedCount; i += 1) {
            Map<String, Object> oldLine = removed.get(i);
            Map<String, Object> newLine = added.get(i);
            collapsed.add(chapterDiffLine(
                "changed",
                (Integer) oldLine.get("oldLineNumber"),
                (Integer) newLine.get("newLineNumber"),
                stringValue(oldLine.get("oldText"), ""),
                stringValue(newLine.get("newText"), "")
            ));
        }
        for (int i = changedCount; i < removed.size(); i += 1) {
            collapsed.add(removed.get(i));
        }
        for (int i = changedCount; i < added.size(); i += 1) {
            collapsed.add(added.get(i));
        }
        removed.clear();
        added.clear();
    }

    private Map<String, Object> chapterDiffLine(
            String type,
            Integer oldLineNumber,
            Integer newLineNumber,
            String oldText,
            String newText) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("oldLineNumber", oldLineNumber);
        item.put("newLineNumber", newLineNumber);
        item.put("oldText", oldText);
        item.put("newText", newText);
        return item;
    }

    private Path archiveChapterSnapshot(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Path sourceFile,
            Map<String, Object> chapter,
            String reason,
            String restoreFromVersionId) {
        Path versionsDir = projectRoot(projectId).resolve("novel").resolve("chapters").resolve("versions")
            .resolve(bookId).resolve("volume_" + volumeNumber);
        int version = intValue(valueOf(chapter, "version", "version"));
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path snapshotFile = versionsDir.resolve("chapter_" + chapterNumber + "_v" + version + "_" + timestamp + ".json");
        Map<String, Object> snapshot = new LinkedHashMap<>(chapter);
        snapshot.put("archived_at", LocalDateTime.now().toString());
        snapshot.put("source_path", relative(projectId, sourceFile));
        snapshot.put("archive_reason", reason);
        snapshot.put("restore_from_version_id", restoreFromVersionId);
        writeJson(snapshotFile, snapshot);
        return snapshotFile;
    }

    private Path archiveOutlineSnapshot(
            String projectId,
            String bookId,
            Path sourceFile,
            Map<String, Object> outline,
            String reason) {
        Path versionsDir = projectRoot(projectId).resolve("novel").resolve("outline").resolve("versions")
            .resolve(bookId);
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path snapshotFile = versionsDir.resolve(bookId + "_outline_" + timestamp + ".json");
        Map<String, Object> snapshot = new LinkedHashMap<>(outline);
        snapshot.put("archived_at", LocalDateTime.now().toString());
        snapshot.put("source_path", relative(projectId, sourceFile));
        snapshot.put("archive_reason", reason);
        writeJson(snapshotFile, snapshot);
        return snapshotFile;
    }

    private Path archiveTextSnapshot(
            String projectId,
            Path sourceFile,
            Path versionsDir,
            String namePrefix,
            String extension) {
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path snapshotFile = versionsDir.resolve(namePrefix + "_" + timestamp + "." + extension);
        try {
            Files.createDirectories(snapshotFile.getParent());
            Files.copy(sourceFile, snapshotFile);
            return snapshotFile;
        } catch (IOException e) {
            throw new RuntimeException("归档文本文件失败: " + sourceFile.getFileName(), e);
        }
    }

    private Path writeRestoreReport(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            String targetStage,
            String versionId,
            Path versionFile,
            Path restoredFile,
            Path previousSnapshotPath,
            Map<String, Object> before,
            Map<String, Object> after) {
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(bookId).resolve("volume_" + volumeNumber);
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path reportFile = reviewsDir.resolve("chapter_" + chapterNumber + "_restore_" + timestamp + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("review_type", "chapter_restore");
        report.put("book_id", bookId);
        report.put("volume_number", volumeNumber);
        report.put("chapter_number", chapterNumber);
        report.put("created_at", LocalDateTime.now().toString());
        report.put("target_stage", targetStage);
        report.put("restored_from_version_id", versionId);
        report.put("restored_from_path", relative(projectId, versionFile));
        report.put("restored_to_path", relative(projectId, restoredFile));
        report.put("previous_snapshot_path", previousSnapshotPath != null ? relative(projectId, previousSnapshotPath) : null);
        report.put("version_before", before == null ? null : valueOf(before, "version", "version"));
        report.put("version_after", valueOf(after, "version", "version"));
        report.put("word_count_before", before == null ? null : valueOf(before, "word_count", "wordCount"));
        report.put("word_count_after", valueOf(after, "word_count", "wordCount"));
        writeJson(reportFile, report);
        return reportFile;
    }

    private Path writeFinalizeReport(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Path sourceFile,
            Path finalFile,
            Path previousFinalSnapshotPath,
            Map<String, Object> before,
            Map<String, Object> after,
            boolean overwrite,
            boolean createVersionSnapshot,
            String finalizer,
            String finalizeNote,
            Task memoryTask) {
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(bookId).resolve("volume_" + volumeNumber);
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path reportFile = reviewsDir.resolve("chapter_" + chapterNumber + "_finalize_" + timestamp + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("review_type", "chapter_finalize");
        report.put("book_id", bookId);
        report.put("volume_number", volumeNumber);
        report.put("chapter_number", chapterNumber);
        report.put("chapter_id", chapterId(after, finalFile));
        report.put("chapter_title", valueOf(after, "chapter_title", "chapterTitle"));
        report.put("created_at", LocalDateTime.now().toString());
        report.put("source_path", relative(projectId, sourceFile));
        report.put("final_path", relative(projectId, finalFile));
        report.put("previous_final_snapshot_path", previousFinalSnapshotPath != null ? relative(projectId, previousFinalSnapshotPath) : null);
        report.put("overwrite", overwrite);
        report.put("create_version_snapshot", createVersionSnapshot);
        report.put("finalizer", finalizer);
        report.put("finalize_note", finalizeNote);
        report.put("version_before", before == null ? null : valueOf(before, "version", "version"));
        report.put("version_after", valueOf(after, "version", "version"));
        report.put("word_count_before", before == null ? null : valueOf(before, "word_count", "wordCount"));
        report.put("word_count_after", valueOf(after, "word_count", "wordCount"));
        report.put("memory_task_id", memoryTask != null ? memoryTask.getId() : null);
        report.put("memory_task_status", memoryTask != null ? memoryTask.getStatus().name() : null);
        report.put("finalized_at", valueOf(after, "finalized_at", "finalizedAt"));
        writeJson(reportFile, report);
        return reportFile;
    }

    private Path writeHumanReviewReport(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Path chapterFile,
            Map<String, Object> chapter,
            Map<String, Object> decisionRecord) {
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(bookId).resolve("volume_" + volumeNumber);
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path reportFile = reviewsDir.resolve("chapter_" + chapterNumber + "_human_review_" + timestamp + ".json");
        Map<String, Object> report = new LinkedHashMap<>(decisionRecord);
        report.put("book_id", bookId);
        report.put("volume_number", volumeNumber);
        report.put("chapter_number", chapterNumber);
        report.put("chapter_id", chapterId(chapter, chapterFile));
        report.put("chapter_title", valueOf(chapter, "chapter_title", "chapterTitle"));
        report.put("chapter_path", relative(projectId, chapterFile));
        report.put("created_at", LocalDateTime.now().toString());
        report.put("version_before", valueOf(chapter, "version", "version"));
        report.put("version_after", valueOf(chapter, "version", "version"));
        report.put("word_count_before", valueOf(chapter, "word_count", "wordCount"));
        report.put("word_count_after", valueOf(chapter, "word_count", "wordCount"));
        writeJson(reportFile, report);
        return reportFile;
    }

    private Path writeManualEditReport(
            String projectId,
            String bookId,
            Integer volumeNumber,
            Integer chapterNumber,
            Path chapterFile,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> editRecord) {
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(bookId).resolve("volume_" + volumeNumber);
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path reportFile = reviewsDir.resolve("chapter_" + chapterNumber + "_manual_edit_" + timestamp + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("review_type", "chapter_manual_edit");
        report.put("book_id", bookId);
        report.put("volume_number", volumeNumber);
        report.put("chapter_number", chapterNumber);
        report.put("chapter_id", chapterId(after, chapterFile));
        report.put("chapter_title_before", valueOf(before, "chapter_title", "chapterTitle"));
        report.put("chapter_title_after", valueOf(after, "chapter_title", "chapterTitle"));
        report.put("chapter_path", relative(projectId, chapterFile));
        report.put("created_at", LocalDateTime.now().toString());
        report.put("editor", editRecord.get("editor"));
        report.put("edit_note", editRecord.get("edit_note"));
        report.put("content_changed", editRecord.get("content_changed"));
        report.put("title_changed", editRecord.get("title_changed"));
        report.put("snapshot_path", editRecord.get("snapshot_path"));
        report.put("version_before", editRecord.get("version_before"));
        report.put("version_after", editRecord.get("version_after"));
        report.put("word_count_before", editRecord.get("word_count_before"));
        report.put("word_count_after", editRecord.get("word_count_after"));
        writeJson(reportFile, report);
        return reportFile;
    }

    private Path writeOutlineEditReport(
            String projectId,
            String bookId,
            Path outlineFile,
            Map<String, Object> before,
            Map<String, Object> after,
            Path snapshotPath,
            Path soulSnapshotPath,
            String editor,
            String editNote) {
        Path reviewsDir = projectRoot(projectId).resolve("novel").resolve("reviews")
            .resolve(bookId).resolve("outline");
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TIMESTAMP);
        Path reportFile = reviewsDir.resolve("outline_manual_edit_" + timestamp + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("review_type", "outline_manual_edit");
        report.put("book_id", bookId);
        report.put("outline_path", relative(projectId, outlineFile));
        report.put("created_at", LocalDateTime.now().toString());
        report.put("editor", editor);
        report.put("edit_note", editNote);
        report.put("snapshot_path", snapshotPath != null ? relative(projectId, snapshotPath) : null);
        report.put("soul_snapshot_path", soulSnapshotPath != null ? relative(projectId, soulSnapshotPath) : null);
        report.put("book_title_before", valueOf(before, "book_title", "bookTitle"));
        report.put("book_title_after", valueOf(after, "book_title", "bookTitle"));
        report.put("total_chapters_before", valueOf(before, "total_chapters", "totalChapters"));
        report.put("total_chapters_after", valueOf(after, "total_chapters", "totalChapters"));
        report.put("target_word_count_before", valueOf(before, "target_word_count", "targetWordCount"));
        report.put("target_word_count_after", valueOf(after, "target_word_count", "targetWordCount"));
        writeJson(reportFile, report);
        return reportFile;
    }

    private String normalizeReviewDecision(String rawDecision) {
        String decision = rawDecision == null ? "approved" : rawDecision.trim().toLowerCase();
        return switch (decision) {
            case "approve", "approved", "pass", "passed" -> "approved";
            case "request_changes", "request-change", "changes", "needs_revision", "revision" -> "needs_revision";
            case "reject", "rejected", "fail", "failed" -> "rejected";
            default -> throw new IllegalArgumentException("人工审查决策仅支持 approved/needs_revision/rejected");
        };
    }

    @SuppressWarnings("unchecked")
    private void appendHistory(Map<String, Object> target, String key, Map<String, Object> record) {
        List<Object> history = new ArrayList<>();
        Object existing = target.get(key);
        if (existing instanceof List<?> list) {
            history.addAll(list);
        }
        history.add(record);
        target.put(key, history);
    }

    private boolean isFinalChapterPath(String projectId, Path path) {
        Path finalRoot = projectRoot(projectId).resolve("novel").resolve("chapters").resolve("final").normalize();
        return path.normalize().startsWith(finalRoot);
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

    private void writeJson(Path file, Map<String, Object> data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException("写入章节终稿失败: " + file.getFileName(), e);
        }
    }

    private void writeChapterText(Path jsonFile, Map<String, Object> chapter) {
        Path textFile = jsonFile.resolveSibling(stripSuffix(jsonFile.getFileName().toString(), ".json") + ".txt");
        String title = stringValue(valueOf(chapter, "chapter_title", "chapterTitle"), "未命名章节");
        String content = stringValue(chapter.get("content"), "");
        try {
            Files.writeString(textFile, title + "\n\n" + content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入章节终稿文本失败: " + textFile.getFileName(), e);
        }
    }

    private void writeText(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入文本文件失败: " + file.getFileName(), e);
        }
    }

    private Map<String, Object> readSoulGovernance(String projectId, String bookId) {
        Path metaFile = soulGovernanceFile(projectId);
        Map<String, Object> governance = Files.exists(metaFile)
            ? camelizeMap(readJson(metaFile))
            : new LinkedHashMap<>();
        governance.putIfAbsent("bookId", bookId);
        governance.putIfAbsent("locked", false);
        governance.putIfAbsent("approvalStatus", "pending_review");
        governance.put("path", relative(projectId, metaFile));
        governance.put("exists", Files.exists(metaFile));
        governance.put("updatedAt", Files.exists(metaFile) ? modifiedAt(metaFile) : "");
        return governance;
    }

    private Path soulGovernanceFile(String projectId) {
        return projectRoot(projectId).resolve("novel").resolve("soul").resolve("project_soul_meta.json");
    }

    private Map<String, Object> readOutlineGovernance(String projectId, String bookId) {
        Path metaFile = outlineGovernanceFile(projectId, bookId);
        Map<String, Object> governance = Files.exists(metaFile)
            ? camelizeMap(readJson(metaFile))
            : new LinkedHashMap<>();
        governance.putIfAbsent("bookId", bookId);
        governance.putIfAbsent("locked", false);
        governance.putIfAbsent("approvalStatus", "pending_review");
        governance.put("path", relative(projectId, metaFile));
        governance.put("exists", Files.exists(metaFile));
        governance.put("updatedAt", Files.exists(metaFile) ? modifiedAt(metaFile) : "");
        return governance;
    }

    private Path outlineGovernanceFile(String projectId, String bookId) {
        return projectRoot(projectId).resolve("novel").resolve("outline").resolve(bookId + "_outline_meta.json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOutlinePayload(Map<String, Object> options) {
        Object outlineValue = valueOf(options, "outline", "outline");
        if (outlineValue instanceof Map<?, ?> rawMap) {
            return new LinkedHashMap<>((Map<String, Object>) rawMap);
        }
        if (outlineValue instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, new TypeReference<>() {});
            } catch (IOException e) {
                throw new IllegalArgumentException("大纲 JSON 格式无效");
            }
        }

        Map<String, Object> outline = new LinkedHashMap<>(options);
        outline.remove("projectSoul");
        outline.remove("project_soul");
        outline.remove("editor");
        outline.remove("editNote");
        outline.remove("edit_note");
        outline.remove("createVersionSnapshot");
        outline.remove("create_version_snapshot");
        outline.remove("overrideOutlineLock");
        outline.remove("override_outline_lock");
        return outline;
    }

    private void removeReadOnlyOutlineFields(Map<String, Object> outline) {
        outline.remove("outline_path");
        outline.remove("project_soul");
        outline.remove("project_soul_path");
        outline.remove("project_soul_governance");
        outline.remove("outline_governance");
        outline.remove("chapter_count");
        removeNestedKey(outline, "chapter_count");
    }

    @SuppressWarnings("unchecked")
    private void removeNestedKey(Object value, String keyToRemove) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            map.remove(keyToRemove);
            map.values().forEach(child -> removeNestedKey(child, keyToRemove));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> removeNestedKey(child, keyToRemove));
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

    private Object decamelize(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((key, childValue) -> result.put(toSnakeCase(String.valueOf(key)), decamelize(childValue)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::decamelize).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> camelizeMap(Map<String, Object> map) {
        return (Map<String, Object>) camelize(map);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decamelizeMap(Map<String, Object> map) {
        return (Map<String, Object>) decamelize(map);
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

    private void validateId(String value, String fieldName) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("非法" + fieldName + ": " + value);
        }
    }

    private String relative(String projectId, Path path) {
        return projectRoot(projectId).relativize(path).toString().replace("\\", "/");
    }

    private Path projectRoot(String projectId) {
        return Paths.get(basePath, "projects", projectId).normalize();
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

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Object valueOf(Map<String, Object> map, String snakeKey, String camelKey) {
        if (map.containsKey(snakeKey)) {
            return map.get(snakeKey);
        }
        return map.get(camelKey);
    }

    private String chapterId(Map<String, Object> chapter, Path fallbackFile) {
        return stringValue(valueOf(chapter, "chapter_id", "chapterId"), stripSuffix(fallbackFile.getFileName().toString(), ".json"));
    }

    private boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) {
            value = options.get(toSnakeCase(key));
        }
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private int intOption(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value == null) {
            value = options.get(toSnakeCase(key));
        }
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String toSnakeCase(String key) {
        StringBuilder result = new StringBuilder();
        for (char ch : key.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                result.append('_').append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
