package com.novel.system.controller;

import com.novel.system.service.BookArtifactService;
import com.novel.system.service.ChapterArtifactService;
import com.novel.system.service.OutlineArtifactService;
import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/books")
@RequiredArgsConstructor
public class BookController {

    private final BookArtifactService bookArtifactService;
    private final ChapterArtifactService chapterArtifactService;
    private final OutlineArtifactService outlineArtifactService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listBooks(@PathVariable String projectId) {
        return ResponseEntity.ok(bookArtifactService.listBooks(projectId));
    }

    @GetMapping("/outlines/db")
    public ResponseEntity<List<Map<String, Object>>> listOutlinesFromDb(@PathVariable String projectId) {
        return ResponseEntity.ok(outlineArtifactService.listOutlines(projectId));
    }

    @GetMapping("/{bookId}/outline")
    public ResponseEntity<Map<String, Object>> getOutline(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(bookArtifactService.getOutline(projectId, bookId));
    }

    @GetMapping("/{bookId}/outline/db")
    public ResponseEntity<Map<String, Object>> getOutlineFromDb(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(outlineArtifactService.getOutline(projectId, bookId));
    }

    @PostMapping("/{bookId}/outline/sync")
    public ResponseEntity<Map<String, Object>> syncOutlineToDb(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(outlineArtifactService.syncOutlineFromWorkspace(projectId, bookId));
    }

    @PatchMapping("/{bookId}/outline")
    public ResponseEntity<Map<String, Object>> updateOutline(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateOutline(projectId, bookId, request));
    }

    @PostMapping("/{bookId}/outline/lock")
    public ResponseEntity<Map<String, Object>> lockOutline(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateOutlineGovernance(projectId, bookId, "lock", request));
    }

    @PostMapping("/{bookId}/outline/unlock")
    public ResponseEntity<Map<String, Object>> unlockOutline(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateOutlineGovernance(projectId, bookId, "unlock", request));
    }

    @PostMapping("/{bookId}/outline/approve")
    public ResponseEntity<Map<String, Object>> approveOutline(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateOutlineGovernance(projectId, bookId, "approve", request));
    }

    @GetMapping("/{bookId}/outline/reviews")
    public ResponseEntity<List<Map<String, Object>>> listOutlineReviews(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(bookArtifactService.listOutlineReviews(projectId, bookId));
    }

    @GetMapping("/{bookId}/soul")
    public ResponseEntity<Map<String, Object>> getProjectSoul(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(bookArtifactService.getProjectSoul(projectId, bookId));
    }

    @GetMapping("/{bookId}/soul/versions")
    public ResponseEntity<List<Map<String, Object>>> listProjectSoulVersions(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(bookArtifactService.listProjectSoulVersions(projectId, bookId));
    }

    @GetMapping("/{bookId}/soul/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> getProjectSoulVersion(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable String versionId) {
        return ResponseEntity.ok(bookArtifactService.getProjectSoulVersion(projectId, bookId, versionId));
    }

    @PostMapping("/{bookId}/soul/versions/{versionId}/restore")
    public ResponseEntity<Map<String, Object>> restoreProjectSoulVersion(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable String versionId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.restoreProjectSoulVersion(
            projectId,
            bookId,
            versionId,
            request == null ? Map.of() : request
        ));
    }

    @PostMapping("/{bookId}/soul/lock")
    public ResponseEntity<Map<String, Object>> lockProjectSoul(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateProjectSoulGovernance(projectId, bookId, "lock", request));
    }

    @PostMapping("/{bookId}/soul/unlock")
    public ResponseEntity<Map<String, Object>> unlockProjectSoul(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateProjectSoulGovernance(projectId, bookId, "unlock", request));
    }

    @PostMapping("/{bookId}/soul/approve")
    public ResponseEntity<Map<String, Object>> approveProjectSoul(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateProjectSoulGovernance(projectId, bookId, "approve", request));
    }

    @GetMapping("/{bookId}/chapters")
    public ResponseEntity<List<Map<String, Object>>> listChapters(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(bookArtifactService.listChapters(projectId, bookId));
    }

    @GetMapping("/{bookId}/chapters/db")
    public ResponseEntity<Map<String, Object>> listChaptersFromDb(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(chapterArtifactService.listChapters(projectId, bookId));
    }

    @PostMapping("/{bookId}/chapters/sync")
    public ResponseEntity<Map<String, Object>> syncChaptersToDb(
            @PathVariable String projectId,
            @PathVariable String bookId) {
        return ResponseEntity.ok(chapterArtifactService.syncChaptersFromWorkspace(projectId, bookId));
    }

    @GetMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}")
    public ResponseEntity<Map<String, Object>> getChapter(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber) {
        return ResponseEntity.ok(bookArtifactService.getChapter(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber
        ));
    }

    @GetMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/db")
    public ResponseEntity<Map<String, Object>> getChapterFromDb(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber) {
        return ResponseEntity.ok(chapterArtifactService.getChapter(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            "auto"
        ));
    }

    @PostMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/sync")
    public ResponseEntity<Map<String, Object>> syncChapterToDb(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        String stage = request != null && request.get("stage") != null
            ? String.valueOf(request.get("stage"))
            : "auto";
        return ResponseEntity.ok(chapterArtifactService.syncChapterFromWorkspace(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            stage
        ));
    }

    @PostMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/revision")
    public ResponseEntity<TaskResponse> reviseChapter(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        Task task = bookArtifactService.reviseChapter(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            request
        );
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @GetMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/versions")
    public ResponseEntity<List<Map<String, Object>>> listChapterVersions(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber) {
        return ResponseEntity.ok(bookArtifactService.listChapterVersions(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber
        ));
    }

    @GetMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/reviews")
    public ResponseEntity<List<Map<String, Object>>> listChapterReviews(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber) {
        return ResponseEntity.ok(bookArtifactService.listChapterReviews(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber
        ));
    }

    @PostMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/versions/{versionId}/restore")
    public ResponseEntity<Map<String, Object>> restoreChapterVersion(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber,
            @PathVariable String versionId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.restoreChapterVersion(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            versionId,
            request
        ));
    }

    @PostMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/diff")
    public ResponseEntity<Map<String, Object>> diffChapter(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.diffChapter(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            request
        ));
    }

    @PostMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/review")
    public ResponseEntity<Map<String, Object>> reviewChapter(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.reviewChapter(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            request
        ));
    }

    @PatchMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}")
    public ResponseEntity<Map<String, Object>> updateChapter(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateChapter(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            request
        ));
    }

    @PostMapping("/{bookId}/chapters/{volumeNumber}/{chapterNumber}/finalize")
    public ResponseEntity<Map<String, Object>> finalizeChapter(
            @PathVariable String projectId,
            @PathVariable String bookId,
            @PathVariable Integer volumeNumber,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.finalizeChapter(
            projectId,
            bookId,
            volumeNumber,
            chapterNumber,
            request
        ));
    }
}
