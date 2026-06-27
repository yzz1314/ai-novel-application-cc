package com.novel.system.controller;

import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import com.novel.system.service.BookArtifactService;
import com.novel.system.service.OutlineArtifactService;
import com.novel.system.service.TaskExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.HashMap;

@RestController
@RequiredArgsConstructor
public class ChapterController {

    private final BookArtifactService bookArtifactService;
    private final OutlineArtifactService outlineArtifactService;
    private final TaskExecutorService taskExecutorService;

    /**
     * 文档中的大纲数据库读取入口：默认使用最新书籍。
     */
    @GetMapping("/api/projects/{projectId}/outline/db")
    public ResponseEntity<Map<String, Object>> getDefaultOutlineFromDb(@PathVariable String projectId) {
        return ResponseEntity.ok(outlineArtifactService.getOutline(projectId, "default"));
    }

    /**
     * 文档中的大纲数据库同步入口：默认使用最新书籍。
     */
    @PostMapping("/api/projects/{projectId}/outline/sync")
    public ResponseEntity<Map<String, Object>> syncDefaultOutlineToDb(@PathVariable String projectId) {
        return ResponseEntity.ok(outlineArtifactService.syncOutlineFromWorkspace(projectId, "default"));
    }

    /**
     * 文档中的大纲更新入口：默认使用最新书籍。
     */
    @PatchMapping("/api/projects/{projectId}/outline")
    public ResponseEntity<Map<String, Object>> updateDefaultOutline(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateOutline(
            projectId,
            "default",
            request
        ));
    }

    /**
     * 文档中的大纲审查入口：默认使用最新书籍。
     */
    @PostMapping("/api/projects/{projectId}/outline/review")
    public ResponseEntity<TaskResponse> reviewDefaultOutline(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> options = request != null ? new HashMap<>(request) : new HashMap<>();
        options.putIfAbsent("project_id", projectId);
        options.putIfAbsent("book_id", "default");
        Task task = taskExecutorService.createTask(
            projectId,
            "outline_review",
            "outline_review",
            Map.of("book_id", options.get("book_id")),
            options
        );
        taskExecutorService.executeTaskAsync(task.getId());
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    /**
     * 文档中的简写终稿确认入口：默认使用最新书籍、第1卷。
     */
    @PostMapping("/api/projects/{projectId}/chapters/{chapterNumber}/finalize")
    public ResponseEntity<Map<String, Object>> finalizeDefaultChapter(
            @PathVariable String projectId,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.finalizeChapter(
            projectId,
            "default",
            1,
            chapterNumber,
            request
        ));
    }

    /**
     * 文档中的简写章节人工审查入口：默认使用最新书籍、第1卷。
     */
    @PostMapping("/api/projects/{projectId}/chapters/{chapterNumber}/review")
    public ResponseEntity<Map<String, Object>> reviewDefaultChapter(
            @PathVariable String projectId,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.reviewChapter(
            projectId,
            "default",
            1,
            chapterNumber,
            request
        ));
    }

    /**
     * 文档中的简写章节更新入口：默认使用最新书籍、第1卷。
     */
    @PostMapping("/api/projects/{projectId}/chapters/{chapterNumber}/diff")
    public ResponseEntity<Map<String, Object>> diffDefaultChapter(
            @PathVariable String projectId,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.diffChapter(
            projectId,
            "default",
            1,
            chapterNumber,
            request
        ));
    }

    @PatchMapping("/api/projects/{projectId}/chapters/{chapterNumber}")
    public ResponseEntity<Map<String, Object>> updateDefaultChapter(
            @PathVariable String projectId,
            @PathVariable Integer chapterNumber,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(bookArtifactService.updateChapter(
            projectId,
            "default",
            1,
            chapterNumber,
            request
        ));
    }
}
