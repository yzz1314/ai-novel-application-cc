package com.novel.system.controller;

import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.service.TaskExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
        return ResponseEntity.ok(TaskResponse.from(task, taskExecutorService.getTaskProgress(task)));
    }

    /**
     * 列出项目的任务
     */
    @GetMapping
    public ResponseEntity<List<TaskResponse>> listTasks(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean recent,
            @RequestParam(defaultValue = "100") int limit) {

        List<Task> tasks;
        if (projectId != null && recent) {
            tasks = taskExecutorService.listRecentTasks(projectId);
        } else {
            TaskStatus taskStatus = parseStatus(status);
            tasks = taskExecutorService.listTasks(projectId, taskStatus, limit);
        }

        List<TaskResponse> responses = tasks.stream()
            .map(task -> TaskResponse.from(task, taskExecutorService.getTaskProgress(task)))
            .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    private TaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported task status: " + status);
        }
    }

    /**
     * 取消任务
     */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskResponse> cancelTask(@PathVariable String taskId) {
        Task task = taskExecutorService.cancelTask(taskId);
        return ResponseEntity.ok(TaskResponse.from(task, taskExecutorService.getTaskProgress(task)));
    }

    /**
     * 重试任务
     */
    @PostMapping("/{taskId}/retry")
    public ResponseEntity<TaskResponse> retryTask(@PathVariable String taskId) {
        Task task = taskExecutorService.retryTask(taskId);
        return ResponseEntity.ok(TaskResponse.from(task, taskExecutorService.getTaskProgress(task)));
    }

    /**
     * 从任务 checkpoint 创建恢复任务。
     */
    @PostMapping("/{taskId}/resume")
    public ResponseEntity<TaskResponse> resumeTask(
            @PathVariable String taskId,
            @RequestBody(required = false) Map<String, Object> resumeInput) {
        Task task = taskExecutorService.resumeTask(taskId, resumeInput);
        return ResponseEntity.ok(TaskResponse.from(task, taskExecutorService.getTaskProgress(task)));
    }

    /**
     * 获取任务日志和诊断信息
     */
    @GetMapping("/{taskId}/logs")
    public ResponseEntity<Map<String, Object>> getTaskLogs(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "80") int tailLines) {
        return ResponseEntity.ok(taskExecutorService.getTaskLogs(taskId, tailLines));
    }

    @GetMapping(value = "/{taskId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskLogs(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "80") int tailLines) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        Thread streamThread = new Thread(() -> streamTaskLogSnapshots(emitter, taskId, tailLines));
        streamThread.setName("task-log-stream-" + taskId);
        streamThread.setDaemon(true);
        streamThread.start();
        return emitter;
    }

    private void streamTaskLogSnapshots(SseEmitter emitter, String taskId, int tailLines) {
        try {
            while (true) {
                Map<String, Object> logs = taskExecutorService.getTaskLogs(taskId, tailLines);
                emitter.send(SseEmitter.event().name("snapshot").data(logs));
                if (isTerminalStatus(logs.get("status"))) {
                    emitter.send(SseEmitter.event().name("complete").data(logs));
                    emitter.complete();
                    return;
                }
                Thread.sleep(1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage())));
            } catch (IOException ignored) {
                // Client disconnected before the error event could be delivered.
            }
            emitter.completeWithError(e);
        }
    }

    private boolean isTerminalStatus(Object status) {
        return status != null && List.of("SUCCESS", "FAILED", "CANCELLED").contains(status.toString());
    }
}
