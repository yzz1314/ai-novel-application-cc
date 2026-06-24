package com.novel.system.service;

import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.repository.TaskRepository;
import com.novel.system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private final TaskRepository taskRepository;
    private final PythonClientService pythonClientService;

    /**
     * 创建任务
     */
    @Transactional
    public Task createTask(String projectId, String taskType, String agentName,
                          Map<String, Object> inputRefs, Map<String, Object> parameters) {

        Task task = new Task();
        task.setProjectId(projectId);
        task.setTaskType(taskType);
        task.setAgentName(agentName);
        task.setInputRefs(inputRefs);
        task.setParameters(parameters);
        task.setStatus(TaskStatus.PENDING);

        return taskRepository.save(task);
    }

    /**
     * 异步执行任务
     */
    @Async
    @Transactional
    public CompletableFuture<Task> executeTaskAsync(String taskId) {
        log.info("Starting async execution of task: {}", taskId);

        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("任务不存在: " + taskId));

        try {
            // 更新状态为运行中
            task.setStatus(TaskStatus.RUNNING);
            task.setStartedAt(LocalDateTime.now());
            taskRepository.save(task);

            // 构建请求
            Map<String, Object> request = pythonClientService.buildAgentRequest(
                task.getId(),
                task.getProjectId(),
                task.getTaskType(),
                task.getInputRefs(),
                task.getParameters()
            );

            // 调用Python服务
            Map<String, Object> response = pythonClientService.callAgent(
                task.getAgentName(),
                request
            );

            // 更新任务结果
            task.setStatus(TaskStatus.valueOf((String) response.get("status")));
            task.setOutputRefs((Map<String, Object>) response.get("output_refs"));
            task.setResult((Map<String, Object>) response.get("structured_output"));
            task.setErrors((Map<String, Object>) response.get("errors"));
            task.setWarnings((Map<String, Object>) response.get("warnings"));
            task.setMetrics((Map<String, Object>) response.get("metrics"));
            task.setCheckpointRef((String) response.get("checkpoint_ref"));
            task.setFinishedAt(LocalDateTime.now());

            taskRepository.save(task);

            log.info("Task completed successfully: {}", taskId);

        } catch (Exception e) {
            log.error("Task failed: {}", taskId, e);

            task.setStatus(TaskStatus.FAILED);
            task.setFinishedAt(LocalDateTime.now());

            // 保存错误信息
            Map<String, Object> errors = Map.of(
                "error_type", e.getClass().getSimpleName(),
                "error_message", e.getMessage()
            );
            task.setErrors(errors);

            taskRepository.save(task);

            // 检查是否需要重试
            if (task.getRetryCount() < task.getMaxRetries() && isRetryable(e)) {
                task.setRetryCount(task.getRetryCount() + 1);
                task.setStatus(TaskStatus.PENDING);
                taskRepository.save(task);

                log.info("Retrying task: {} (attempt {})", taskId, task.getRetryCount());
                return executeTaskAsync(taskId);
            }
        }

        return CompletableFuture.completedFuture(task);
    }

    private boolean isRetryable(Exception e) {
        // 网络错误、超时、速率限制等可重试
        return e instanceof java.net.SocketTimeoutException ||
               e instanceof java.net.ConnectException ||
               e.getMessage().contains("rate limit") ||
               e.getMessage().contains("timeout");
    }

    /**
     * 获取任务
     */
    public Task getTask(String taskId) {
        return taskRepository.findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("任务不存在: " + taskId));
    }

    /**
     * 列出项目的任务
     */
    public List<Task> listTasksByProject(String projectId) {
        return taskRepository.findByProjectId(projectId);
    }

    /**
     * 列出项目最近的任务
     */
    public List<Task> listRecentTasks(String projectId) {
        return taskRepository.findTop10ByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * 取消任务
     */
    @Transactional
    public Task cancelTask(String taskId) {
        Task task = getTask(taskId);

        if (task.getStatus() == TaskStatus.RUNNING) {
            // 通知Python服务取消任务
            pythonClientService.cancelTask(taskId);
        }

        task.setStatus(TaskStatus.CANCELLED);
        task.setFinishedAt(LocalDateTime.now());

        return taskRepository.save(task);
    }
}
