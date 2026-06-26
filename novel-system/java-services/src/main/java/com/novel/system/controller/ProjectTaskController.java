package com.novel.system.controller;

import com.novel.system.dto.request.ExecuteTaskRequest;
import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.Task;
import com.novel.system.service.TaskExecutorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;

@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
@RequiredArgsConstructor
public class ProjectTaskController {

    private final TaskExecutorService taskExecutorService;

    /**
     * 创建并异步执行项目任务。
     */
    @PostMapping("/execute")
    public ResponseEntity<TaskResponse> executeProjectTask(
            @PathVariable String projectId,
            @Valid @RequestBody ExecuteTaskRequest request) {

        Task task = taskExecutorService.createTask(
            projectId,
            request.resolvedTaskType(),
            request.getAgentName(),
            request.getInputRefs() != null ? request.getInputRefs() : new HashMap<>(),
            request.resolvedParameters() != null ? request.resolvedParameters() : new HashMap<>()
        );

        taskExecutorService.executeTaskAsync(task.getId());
        return ResponseEntity.ok(TaskResponse.from(task));
    }
}
