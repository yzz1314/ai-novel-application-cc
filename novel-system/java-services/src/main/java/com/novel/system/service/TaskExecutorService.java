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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private final TaskRepository taskRepository;
    private final PythonClientService pythonClientService;
    private final SampleService sampleService;
    private final AnalysisResultService analysisResultService;
    private final ModelProfileService modelProfileService;
    private final SkillService skillService;
    private final OutlineArtifactService outlineArtifactService;
    private final ChapterArtifactService chapterArtifactService;
    private final MemoryArtifactService memoryArtifactService;
    private final GraphArtifactDbService graphArtifactDbService;
    private final RetrievalArtifactDbService retrievalArtifactDbService;

    /**
     * 创建任务
     */
    @Transactional
    public Task createTask(String projectId, String taskType, String agentName,
                          Map<String, Object> inputRefs, Map<String, Object> parameters) {
        Map<String, Object> resolvedParameters = parameters != null
            ? new HashMap<>(parameters)
            : new HashMap<>();
        if (!resolvedParameters.containsKey("model_profile_id")) {
            String defaultProfileId = modelProfileService.getDefaultProfileId();
            if (defaultProfileId != null && !defaultProfileId.isBlank()) {
                resolvedParameters.put("model_profile_id", defaultProfileId);
            }
        }

        Task task = new Task();
        task.setProjectId(projectId);
        task.setTaskType(taskType);
        task.setAgentName(agentName);
        task.setInputRefs(inputRefs);
        task.setParameters(resolvedParameters);
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
            task.setStatus(mapPythonStatus(response.get("status")));
            task.setOutputRefs(asJsonMap(response.get("output_refs")));
            task.setResult(asJsonMap(response.get("structured_output")));
            task.setErrors(asJsonMap(response.get("errors")));
            task.setWarnings(asJsonMap(response.get("warnings")));
            task.setMetrics(asJsonMap(response.get("metrics")));
            task.setCheckpointRef((String) response.get("checkpoint_ref"));
            task.setFinishedAt(LocalDateTime.now());

            taskRepository.save(task);
            handleSuccessfulTaskSideEffects(task);

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

    private void handleSuccessfulTaskSideEffects(Task task) {
        if (task.getStatus() != TaskStatus.SUCCESS && task.getStatus() != TaskStatus.PARTIAL) {
            return;
        }

        if ("sample_import".equals(task.getTaskType())) {
            Object sampleId = taskValue(task, "sample_id");
            if (sampleId == null) {
                return;
            }

            Map<String, Object> result = task.getResult();
            String title = result != null && result.get("title") != null
                ? result.get("title").toString()
                : null;
            Integer totalChars = toInteger(result != null ? result.get("total_chars") : null);
            Integer totalChapters = toInteger(result != null ? result.get("total_chapters") : null);

            sampleService.updateSampleMetadata(sampleId.toString(), title, totalChars, totalChapters);
            sampleService.syncSampleStructureFromWorkspace(task.getProjectId(), sampleId.toString());
            sampleService.updateSampleStatus(sampleId.toString(), com.novel.system.entity.Sample.SampleStatus.CHUNKED);
        } else if ("full_text_analysis".equals(task.getTaskType())) {
            Object sampleId = taskValue(task, "sample_id");
            if (sampleId != null) {
                sampleService.syncSampleStructureFromWorkspace(task.getProjectId(), sampleId.toString());
                analysisResultService.syncAnalysisResultsFromWorkspace(task.getProjectId(), sampleId.toString());
                sampleService.updateSampleStatus(sampleId.toString(), com.novel.system.entity.Sample.SampleStatus.ANALYZED);
            }
        } else if ("skill_generation".equals(task.getTaskType())) {
            skillService.syncSkillProfile(task.getProjectId());
        } else if ("outline_generation".equals(task.getTaskType()) || "outline_review".equals(task.getTaskType())) {
            Object bookId = taskValue(task, "book_id");
            outlineArtifactService.syncOutlineFromWorkspace(
                task.getProjectId(),
                bookId != null ? bookId.toString() : "default"
            );
        } else if ("chapter_writing".equals(task.getTaskType()) || "chapter_revision".equals(task.getTaskType())) {
            Object bookId = taskValue(task, "book_id");
            Integer volumeNumber = toInteger(taskValue(task, "volume_number"));
            Integer chapterNumber = toInteger(taskValue(task, "chapter_number"));
            if (bookId != null && volumeNumber != null && chapterNumber != null) {
                chapterArtifactService.syncChapterFromWorkspace(
                    task.getProjectId(),
                    bookId.toString(),
                    volumeNumber,
                    chapterNumber,
                    "draft"
                );
            }
        } else if ("memory_extraction".equals(task.getTaskType()) || "continuity_check".equals(task.getTaskType())) {
            try {
                memoryArtifactService.syncMemoryFromWorkspace(task.getProjectId(), task.getParameters());
            } catch (Exception e) {
                log.warn("Failed to sync memory artifact for task {}: {}", task.getId(), e.getMessage(), e);
            }
        } else if ("graph_build".equals(task.getTaskType())) {
            try {
                Object bookId = taskValue(task, "book_id");
                Map<String, Object> syncRequest = new HashMap<>();
                if (bookId != null) {
                    syncRequest.put("book_id", bookId.toString());
                }
                graphArtifactDbService.syncGraphFromWorkspace(task.getProjectId(), syncRequest);
            } catch (Exception e) {
                log.warn("Failed to sync graph artifact for task {}: {}", task.getId(), e.getMessage(), e);
            }
        } else if ("retrieval_index".equals(task.getTaskType())) {
            try {
                retrievalArtifactDbService.syncRetrievalFromWorkspace(task.getProjectId(), task.getParameters());
            } catch (Exception e) {
                log.warn("Failed to sync retrieval artifact for task {}: {}", task.getId(), e.getMessage(), e);
            }
        }
    }

    private Object taskValue(Task task, String key) {
        if (task.getInputRefs() != null && task.getInputRefs().containsKey(key)) {
            return task.getInputRefs().get(key);
        }
        if (task.getParameters() != null && task.getParameters().containsKey(key)) {
            return task.getParameters().get(key);
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

    private TaskStatus mapPythonStatus(Object statusValue) {
        String status = statusValue == null ? "failed" : statusValue.toString();
        return switch (status.toLowerCase()) {
            case "success" -> TaskStatus.SUCCESS;
            case "partial" -> TaskStatus.PARTIAL;
            case "cancelled", "canceled" -> TaskStatus.CANCELLED;
            case "failed", "error" -> TaskStatus.FAILED;
            default -> TaskStatus.FAILED;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asJsonMap(Object value) {
        if (value == null) {
            return new HashMap<>();
        }
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }

        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put("items", value);
        return wrapped;
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

    @Transactional
    public Task retryTask(String taskId) {
        Task task = getTask(taskId);
        if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
            throw new IllegalArgumentException("任务正在执行中，不能重试: " + taskId);
        }

        task.setStatus(TaskStatus.PENDING);
        task.setErrors(new HashMap<>());
        task.setWarnings(new HashMap<>());
        task.setStartedAt(null);
        task.setFinishedAt(null);
        taskRepository.save(task);
        executeTaskAsync(task.getId());
        return task;
    }

    @Transactional
    public Task resumeTask(String taskId, Map<String, Object> resumeInput) {
        Task sourceTask = getTask(taskId);
        String checkpointRef = sourceTask.getCheckpointRef();
        if (checkpointRef == null || checkpointRef.isBlank()) {
            throw new IllegalArgumentException("任务没有可恢复的 checkpoint: " + taskId);
        }
        if (sourceTask.getStatus() == TaskStatus.RUNNING || sourceTask.getStatus() == TaskStatus.PENDING) {
            throw new IllegalArgumentException("任务正在执行中，不能恢复: " + taskId);
        }

        Map<String, Object> parameters = sourceTask.getParameters() != null
            ? new HashMap<>(sourceTask.getParameters())
            : new HashMap<>();
        parameters.put("resume_from_checkpoint", checkpointRef);
        if (resumeInput != null && !resumeInput.isEmpty()) {
            parameters.put("resume_input", resumeInput);
        }

        Task resumedTask = createTask(
            sourceTask.getProjectId(),
            sourceTask.getTaskType(),
            sourceTask.getAgentName(),
            sourceTask.getInputRefs() != null ? new HashMap<>(sourceTask.getInputRefs()) : new HashMap<>(),
            parameters
        );
        resumedTask.setCheckpointRef(checkpointRef);
        resumedTask = taskRepository.save(resumedTask);
        executeTaskAsync(resumedTask.getId());
        return resumedTask;
    }

    public Map<String, Object> getTaskLogs(String taskId) {
        Task task = getTask(taskId);
        Map<String, Object> logs = new HashMap<>();
        logs.put("taskId", task.getId());
        logs.put("projectId", task.getProjectId());
        logs.put("taskType", task.getTaskType());
        logs.put("agentName", task.getAgentName());
        logs.put("status", task.getStatus().name());
        logs.put("inputRefs", task.getInputRefs());
        logs.put("parameters", task.getParameters());
        logs.put("outputRefs", task.getOutputRefs());
        logs.put("result", task.getResult());
        logs.put("errors", task.getErrors());
        logs.put("warnings", task.getWarnings());
        logs.put("metrics", task.getMetrics());
        logs.put("checkpointRef", task.getCheckpointRef());
        logs.put("retryCount", task.getRetryCount());
        logs.put("createdAt", task.getCreatedAt());
        logs.put("startedAt", task.getStartedAt());
        logs.put("finishedAt", task.getFinishedAt());
        return logs;
    }
}
