package com.novel.system.controller;

import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import com.novel.system.service.TaskExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskExecutorService taskExecutorService;

    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable String taskId) {
        Task task = taskExecutorService.getTask(taskId);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    /**
     * 列出项目的任务
     */
    @GetMapping
    public ResponseEntity<List<TaskResponse>> listTasks(
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "false") boolean recent) {

        List<Task> tasks;
        if (projectId != null && recent) {
            tasks = taskExecutorService.listRecentTasks(projectId);
        } else if (projectId != null) {
            tasks = taskExecutorService.listTasksByProject(projectId);
        } else {
            throw new IllegalArgumentException("projectId参数是必需的");
        }

        List<TaskResponse> responses = tasks.stream()
            .map(TaskResponse::from)
            .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * 取消任务
     */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskResponse> cancelTask(@PathVariable String taskId) {
        Task task = taskExecutorService.cancelTask(taskId);
        return ResponseEntity.ok(TaskResponse.from(task));
    }
}
