package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.repository.TaskRepository;
import com.novel.system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private static final int MAX_TASK_EVENTS = 200;
    private static final int DEFAULT_TASK_LOG_TAIL_LINES = 80;
    private static final int MAX_TASK_LOG_TAIL_LINES = 500;
    private static final String TASK_EVENT_LOG = "logs/task_events.jsonl";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int MAX_CONFIGURABLE_RETRIES = 10;
    private static final long DEFAULT_RETRY_INITIAL_DELAY_MS = 2_000L;
    private static final long DEFAULT_RETRY_MAX_DELAY_MS = 10_000L;
    private static final double DEFAULT_RETRY_MULTIPLIER = 2.0d;

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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor manualTaskExecutor = Executors.newCachedThreadPool();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

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
        task.setRetryCount(0);
        task.setMaxRetries(resolveMaxRetries(resolvedParameters));

        Task saved = taskRepository.save(task);
        appendTaskEvent(saved, "created", Map.of(
            "inputRefs", inputRefs != null ? inputRefs : Map.of(),
            "parameters", resolvedParameters,
            "retryPolicy", retryPolicyDetails(saved, 0)
        ));
        return saved;
    }

    /**
     * 异步执行任务
     */
    @Async
    public CompletableFuture<Task> executeTaskAsync(String taskId) {
        log.info("Starting async execution of task: {}", taskId);

        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("任务不存在: " + taskId));

        try {
            // 更新状态为运行中
            if (task.getStatus() == TaskStatus.CANCELLED) {
                log.info("Task {} was cancelled before async execution started; skipping Python agent call", taskId);
                appendTaskEvent(task, "start_skipped_cancelled", Map.of("reason", "cancelled_before_start"));
                return CompletableFuture.completedFuture(task);
            }

            task.setStatus(TaskStatus.RUNNING);
            task.setStartedAt(LocalDateTime.now());
            taskRepository.save(task);
            appendTaskEvent(task, "started", Map.of());

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
            Task latestTask = taskRepository.findById(taskId).orElse(task);
            if (latestTask.getStatus() == TaskStatus.CANCELLED) {
                log.info("Task {} was cancelled while Python agent was running; preserving cancelled status", taskId);
                appendTaskEvent(latestTask, "cancel_preserved", Map.of("reason", "cancelled_while_agent_running"));
                return CompletableFuture.completedFuture(latestTask);
            }

            task.setStatus(mapPythonStatus(response.get("status")));
            task.setOutputRefs(asJsonMap(response.get("output_refs")));
            task.setResult(asJsonMap(response.get("structured_output")));
            task.setErrors(asJsonMap(response.get("errors")));
            task.setWarnings(asJsonMap(response.get("warnings")));
            task.setMetrics(mergeMetrics(task.getMetrics(), asJsonMap(response.get("metrics"))));
            task.setCheckpointRef((String) response.get("checkpoint_ref"));
            task.setFinishedAt(LocalDateTime.now());

            taskRepository.save(task);
            appendTaskEvent(task, "finished", Map.of(
                "pythonStatus", response.get("status") != null ? response.get("status") : "",
                "checkpointRef", task.getCheckpointRef() != null ? task.getCheckpointRef() : "",
                "progress", getTaskProgress(task)
            ));
            handleSuccessfulTaskSideEffects(task);

            log.info("Task completed successfully: {}", taskId);

        } catch (Exception e) {
            log.error("Task failed: {}", taskId, e);

            Task latestTask = taskRepository.findById(taskId).orElse(task);
            if (latestTask.getStatus() == TaskStatus.CANCELLED) {
                log.info("Task {} failed after cancellation; preserving cancelled status", taskId);
                appendTaskEvent(latestTask, "cancel_preserved", Map.of("reason", "agent_failed_after_cancellation"));
                return CompletableFuture.completedFuture(latestTask);
            }

            task.setStatus(TaskStatus.FAILED);
            task.setFinishedAt(LocalDateTime.now());

            // 保存错误信息
            Map<String, Object> errors = Map.of(
                "error_type", e.getClass().getSimpleName(),
                "error_message", e.getMessage() != null ? e.getMessage() : ""
            );
            task.setErrors(errors);

            taskRepository.save(task);
            appendTaskEvent(task, "failed", Map.of(
                "errorType", e.getClass().getSimpleName(),
                "errorMessage", e.getMessage() != null ? e.getMessage() : ""
            ));

            // 检查是否需要重试
            if (canAutoRetry(task, e)) {
                int nextRetryCount = safeInt(task.getRetryCount()) + 1;
                Map<String, Object> retryPolicy = retryPolicyDetails(task, nextRetryCount);
                task.setRetryCount(nextRetryCount);
                task.setStatus(TaskStatus.PENDING);
                task.setMetrics(mergeMetrics(task.getMetrics(), Map.of("retry_policy", retryPolicy)));
                taskRepository.save(task);
                appendTaskEvent(task, "auto_retry_scheduled", retryPolicy);

                log.info(
                    "Retrying task: {} (attempt {}, planned delay {} ms)",
                    taskId,
                    task.getRetryCount(),
                    retryPolicy.get("delayMs")
                );
                return executeTaskAsync(taskId);
            }
        }

        return CompletableFuture.completedFuture(task);
    }

    private boolean isRetryable(Exception e) {
        // 网络错误、超时、速率限制等可重试
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return e instanceof java.net.SocketTimeoutException ||
               e instanceof java.net.ConnectException ||
               message.contains("rate limit") ||
               message.contains("timeout");
    }

    private boolean canAutoRetry(Task task, Exception e) {
        return safeInt(task.getRetryCount()) < resolveMaxRetries(task) && isRetryable(e);
    }

    private int resolveMaxRetries(Task task) {
        return task.getMaxRetries() != null ? clamp(task.getMaxRetries(), 0, MAX_CONFIGURABLE_RETRIES) : DEFAULT_MAX_RETRIES;
    }

    @SuppressWarnings("unchecked")
    private int resolveMaxRetries(Map<String, Object> parameters) {
        Object retryPolicy = parameters != null ? parameters.get("retry_policy") : null;
        if (retryPolicy instanceof Map<?, ?> policyMap && policyMap.get("max_retries") != null) {
            return clamp(toInteger(policyMap.get("max_retries")), 0, MAX_CONFIGURABLE_RETRIES, DEFAULT_MAX_RETRIES);
        }
        if (retryPolicy instanceof Map<?, ?> policyMap && policyMap.get("maxRetries") != null) {
            return clamp(toInteger(policyMap.get("maxRetries")), 0, MAX_CONFIGURABLE_RETRIES, DEFAULT_MAX_RETRIES);
        }
        Object maxRetries = parameters != null ? firstValue(parameters, "max_retries", "maxRetries") : null;
        Integer parsed = toInteger(maxRetries);
        return parsed != null ? clamp(parsed, 0, MAX_CONFIGURABLE_RETRIES) : DEFAULT_MAX_RETRIES;
    }

    private Map<String, Object> retryPolicyDetails(Task task, int nextRetryCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        int maxRetries = resolveMaxRetries(task);
        long delayMs = retryDelayMs(task.getParameters(), nextRetryCount);
        details.put("retryCount", nextRetryCount);
        details.put("maxRetries", maxRetries);
        details.put("remainingRetries", Math.max(0, maxRetries - nextRetryCount));
        details.put("delayMs", delayMs);
        details.put("delaySeconds", delayMs / 1000.0d);
        details.put("initialDelayMs", retryInitialDelayMs(task.getParameters()));
        details.put("maxDelayMs", retryMaxDelayMs(task.getParameters()));
        details.put("multiplier", retryMultiplier(task.getParameters()));
        return details;
    }

    private long retryDelayMs(Map<String, Object> parameters, int nextRetryCount) {
        if (nextRetryCount <= 0) {
            return 0L;
        }
        long initial = retryInitialDelayMs(parameters);
        long max = retryMaxDelayMs(parameters);
        double multiplier = retryMultiplier(parameters);
        double computed = initial * Math.pow(multiplier, Math.max(0, nextRetryCount - 1));
        return Math.min(max, Math.max(0L, Math.round(computed)));
    }

    private long retryInitialDelayMs(Map<String, Object> parameters) {
        Long parsed = retryLong(parameters, "initial_delay_ms", "initialDelayMs", "delay_ms", "delayMs");
        return parsed != null ? clamp(parsed, 0L, 300_000L) : DEFAULT_RETRY_INITIAL_DELAY_MS;
    }

    private long retryMaxDelayMs(Map<String, Object> parameters) {
        Long parsed = retryLong(parameters, "max_delay_ms", "maxDelayMs");
        return parsed != null ? clamp(parsed, 0L, 600_000L) : DEFAULT_RETRY_MAX_DELAY_MS;
    }

    private double retryMultiplier(Map<String, Object> parameters) {
        Object value = retryPolicyValue(parameters, "multiplier");
        if (value == null) {
            value = retryPolicyValue(parameters, "backoff_multiplier");
        }
        if (value == null) {
            value = retryPolicyValue(parameters, "backoffMultiplier");
        }
        if (value instanceof Number number) {
            return clamp(number.doubleValue(), 1.0d, 10.0d);
        }
        if (value != null) {
            try {
                return clamp(Double.parseDouble(value.toString()), 1.0d, 10.0d);
            } catch (NumberFormatException ignored) {
                return DEFAULT_RETRY_MULTIPLIER;
            }
        }
        return DEFAULT_RETRY_MULTIPLIER;
    }

    private Long retryLong(Map<String, Object> parameters, String... keys) {
        Object value = retryPolicyValue(parameters, keys);
        Integer parsed = toInteger(value);
        return parsed != null ? parsed.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private Object retryPolicyValue(Map<String, Object> parameters, String... keys) {
        if (parameters == null) {
            return null;
        }
        Object retryPolicy = parameters.get("retry_policy");
        if (retryPolicy instanceof Map<?, ?> policyMap) {
            for (String key : keys) {
                if (policyMap.containsKey(key)) {
                    return policyMap.get(key);
                }
            }
        }
        return firstValue(parameters, keys);
    }

    private Map<String, Object> mergeMetrics(Map<String, Object> metrics, Map<String, Object> updates) {
        Map<String, Object> merged = metrics != null ? new LinkedHashMap<>(metrics) : new LinkedHashMap<>();
        merged.putAll(updates);
        return merged;
    }

    private void handleSuccessfulTaskSideEffects(Task task) {
        if (task.getStatus() != TaskStatus.SUCCESS && task.getStatus() != TaskStatus.PARTIAL) {
            return;
        }

        syncTaskArtifacts(
            task.getId(),
            task.getProjectId(),
            task.getTaskType(),
            task.getInputRefs(),
            task.getParameters(),
            task.getResult()
        );

        if ("workflow".equals(task.getTaskType()) || "workflow".equals(task.getAgentName())) {
            syncWorkflowNodeArtifacts(task);
        }
    }

    @SuppressWarnings("unchecked")
    private void syncWorkflowNodeArtifacts(Task task) {
        Map<String, Object> result = task.getResult();
        Object nodeResultsValue = result != null ? result.get("node_results") : null;
        if (!(nodeResultsValue instanceof Map<?, ?> nodeResults)) {
            return;
        }

        nodeResults.forEach((nodeId, nodeResultValue) -> {
            if (!(nodeResultValue instanceof Map<?, ?> nodeResult)) {
                return;
            }
            Map<String, Object> nodeResultMap = (Map<String, Object>) nodeResult;
            Object statusValue = nodeResultMap.get("status");
            String nodeStatus = statusValue != null ? statusValue.toString() : "";
            if (!"success".equalsIgnoreCase(nodeStatus) && !"partial".equalsIgnoreCase(nodeStatus)) {
                return;
            }

            String taskType = stringValue(nodeResultMap.get("task_type"));
            if (taskType == null || taskType.isBlank()) {
                taskType = taskTypeFromAgentName(stringValue(nodeResultMap.get("agent_name")));
            }
            if (taskType == null || taskType.isBlank()) {
                taskType = taskTypeFromAgentName(stringValue(nodeResultMap.get("agent")));
            }
            if (taskType == null || taskType.isBlank()) {
                return;
            }

            Map<String, Object> inputRefs = asMutableMap(nodeResultMap.get("input_refs"));
            Map<String, Object> parameters = asMutableMap(nodeResultMap.get("parameters"));
            Map<String, Object> structuredOutput = asMutableMap(nodeResultMap.get("structured_output"));
            try {
                syncTaskArtifacts(
                    task.getId() + ":" + nodeId,
                    task.getProjectId(),
                    taskType,
                    inputRefs,
                    parameters,
                    structuredOutput
                );
            } catch (Exception e) {
                log.warn(
                    "Failed to sync workflow node artifact for task {} node {}: {}",
                    task.getId(),
                    nodeId,
                    e.getMessage(),
                    e
                );
            }
        });
    }

    private void syncTaskArtifacts(
            String taskId,
            String projectId,
            String taskType,
            Map<String, Object> inputRefs,
            Map<String, Object> parameters,
            Map<String, Object> result) {
        if ("sample_import".equals(taskType)) {
            Object sampleId = taskValue(inputRefs, parameters, "sample_id");
            if (sampleId == null) {
                return;
            }

            String title = result != null && result.get("title") != null
                ? result.get("title").toString()
                : null;
            Integer totalChars = toInteger(result != null ? result.get("total_chars") : null);
            Integer totalChapters = toInteger(result != null ? result.get("total_chapters") : null);

            sampleService.updateSampleMetadata(sampleId.toString(), title, totalChars, totalChapters);
            sampleService.syncSampleStructureFromWorkspace(projectId, sampleId.toString());
            sampleService.clearSampleAnalysisResults(sampleId.toString());
            sampleService.updateSampleStatus(sampleId.toString(), com.novel.system.entity.Sample.SampleStatus.CHUNKED);
        } else if ("full_text_analysis".equals(taskType) || "analysis_repair".equals(taskType)) {
            Object sampleId = taskValue(inputRefs, parameters, "sample_id");
            if (sampleId != null) {
                sampleService.syncSampleStructureFromWorkspace(projectId, sampleId.toString());
                analysisResultService.syncAnalysisResultsFromWorkspace(projectId, sampleId.toString());
                sampleService.updateSampleStatus(sampleId.toString(), com.novel.system.entity.Sample.SampleStatus.ANALYZED);
            }
        } else if ("coverage_check".equals(taskType)) {
            Object sampleId = taskValue(inputRefs, parameters, "sample_id");
            if (sampleId != null) {
                sampleService.syncSampleStructureFromWorkspace(projectId, sampleId.toString());
                analysisResultService.syncAnalysisResultsFromWorkspace(projectId, sampleId.toString());
            }
        } else if ("skill_generation".equals(taskType)) {
            skillService.syncSkillProfile(projectId);
        } else if ("outline_generation".equals(taskType) || "outline_review".equals(taskType)) {
            Object bookId = taskValue(inputRefs, parameters, "book_id");
            outlineArtifactService.syncOutlineFromWorkspace(
                projectId,
                bookId != null ? bookId.toString() : "default"
            );
        } else if ("chapter_writing".equals(taskType) || "chapter_revision".equals(taskType)) {
            Object bookId = taskValue(inputRefs, parameters, "book_id");
            Integer volumeNumber = toInteger(taskValue(inputRefs, parameters, "volume_number"));
            Integer chapterNumber = toInteger(taskValue(inputRefs, parameters, "chapter_number"));
            if (bookId != null && volumeNumber != null && chapterNumber != null) {
                chapterArtifactService.syncChapterFromWorkspace(
                    projectId,
                    bookId.toString(),
                    volumeNumber,
                    chapterNumber,
                    "draft"
                );
            }
        } else if (
            "memory_extraction".equals(taskType)
                || "continuity_check".equals(taskType)
                || "memory_audit".equals(taskType)
        ) {
            try {
                memoryArtifactService.syncMemoryFromWorkspace(projectId, parameters);
            } catch (Exception e) {
                log.warn("Failed to sync memory artifact for task {}: {}", taskId, e.getMessage(), e);
            }
        } else if ("graph_build".equals(taskType)) {
            try {
                Object bookId = taskValue(inputRefs, parameters, "book_id");
                Map<String, Object> syncRequest = new HashMap<>();
                if (bookId != null) {
                    syncRequest.put("book_id", bookId.toString());
                }
                graphArtifactDbService.syncGraphFromWorkspace(projectId, syncRequest);
            } catch (Exception e) {
                log.warn("Failed to sync graph artifact for task {}: {}", taskId, e.getMessage(), e);
            }
        } else if ("retrieval_index".equals(taskType)) {
            try {
                retrievalArtifactDbService.syncRetrievalFromWorkspace(projectId, parameters);
            } catch (Exception e) {
                log.warn("Failed to sync retrieval artifact for task {}: {}", taskId, e.getMessage(), e);
            }
        }
    }

    private Object taskValue(Map<String, Object> inputRefs, Map<String, Object> parameters, String key) {
        if (inputRefs != null && inputRefs.containsKey(key)) {
            return inputRefs.get(key);
        }
        if (parameters != null && parameters.containsKey(key)) {
            return parameters.get(key);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMutableMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return new HashMap<>((Map<String, Object>) mapValue);
        }
        return new HashMap<>();
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private String taskTypeFromAgentName(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }
        return switch (agentName) {
            case "SampleImportAgent", "sample_import" -> "sample_import";
            case "FullTextAnalysisAgent", "full_text_analysis" -> "full_text_analysis";
            case "AnalysisRepairAgent", "analysis_repair" -> "analysis_repair";
            case "SkillGeneratorAgent", "skill_generation" -> "skill_generation";
            case "OutlineGeneratorAgent", "outline_generation" -> "outline_generation";
            case "OutlineReviewAgent", "outline_review" -> "outline_review";
            case "ChapterWriterAgent", "chapter_writing" -> "chapter_writing";
            case "RevisionAgent", "chapter_revision" -> "chapter_revision";
            case "MemoryExtractorAgent", "memory_extraction" -> "memory_extraction";
            case "MemoryQueryAgent", "memory_query" -> "memory_query";
            case "GraphBuilderAgent", "graph_build" -> "graph_build";
            case "RetrievalIndexAgent", "retrieval_index" -> "retrieval_index";
            default -> null;
        };
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

    public List<Task> listTasks(String projectId, TaskStatus status, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        Pageable pageable = PageRequest.of(0, boundedLimit);
        if (projectId != null && !projectId.isBlank() && status != null) {
            return taskRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status, pageable);
        }
        if (projectId != null && !projectId.isBlank()) {
            return taskRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageable);
        }
        if (status != null) {
            return taskRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        return taskRepository.findAllByOrderByCreatedAtDesc(pageable);
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
        boolean wasRunning = task.getStatus() == TaskStatus.RUNNING;
        boolean pythonCancelNotified = false;
        String pythonCancelError = null;

        if (wasRunning) {
            // 通知Python服务取消任务
            try {
                pythonClientService.cancelTask(taskId);
                pythonCancelNotified = true;
            } catch (RuntimeException e) {
                pythonCancelError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn(
                    "Failed to notify Python service about cancellation for task {}; preserving local cancellation: {}",
                    taskId,
                    pythonCancelError
                );
                log.debug("Python cancellation notification failed for task {}", taskId, e);
            }
        }

        task.setStatus(TaskStatus.CANCELLED);
        task.setFinishedAt(LocalDateTime.now());

        Task saved = taskRepository.save(task);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("wasRunning", wasRunning);
        details.put("pythonCancelNotified", pythonCancelNotified);
        if (pythonCancelError != null) {
            details.put("pythonCancelError", pythonCancelError);
        }
        appendTaskEvent(saved, "cancelled", details);
        return saved;
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
        appendTaskEvent(task, "manual_retry_requested", Map.of("retryCount", task.getRetryCount()));
        dispatchTaskExecution(task.getId());
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
        appendTaskEvent(sourceTask, "resume_requested", Map.of(
            "resumedTaskId", resumedTask.getId(),
            "checkpointRef", checkpointRef
        ));
        appendTaskEvent(resumedTask, "resumed_from_checkpoint", Map.of(
            "sourceTaskId", sourceTask.getId(),
            "checkpointRef", checkpointRef
        ));
        dispatchTaskExecution(resumedTask.getId());
        return resumedTask;
    }

    private void dispatchTaskExecution(String taskId) {
        manualTaskExecutor.execute(() -> executeTaskAsync(taskId));
    }

    public Map<String, Object> getTaskLogs(String taskId) {
        return getTaskLogs(taskId, DEFAULT_TASK_LOG_TAIL_LINES);
    }

    public Map<String, Object> getTaskLogs(String taskId, int tailLines) {
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
        logs.put("progress", getTaskProgress(task));
        logs.put("checkpointRef", task.getCheckpointRef());
        logs.put("retryCount", task.getRetryCount());
        logs.put("maxRetries", task.getMaxRetries());
        logs.put("createdAt", task.getCreatedAt());
        logs.put("startedAt", task.getStartedAt());
        logs.put("finishedAt", task.getFinishedAt());
        logs.put("eventLogPath", taskEventLogRef(task));
        logs.put("projectEventLogPath", TASK_EVENT_LOG);
        logs.put("events", readTaskEvents(task));
        logs.put("eventLogTail", readTaskEventTail(task, tailLines));
        return logs;
    }

    private void appendTaskEvent(Task task, String eventType, Map<String, Object> details) {
        if (task == null || task.getId() == null || task.getProjectId() == null || task.getProjectId().isBlank()) {
            return;
        }

        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("eventType", eventType);
            event.put("taskId", task.getId());
            event.put("projectId", task.getProjectId());
            event.put("taskType", task.getTaskType());
            event.put("agentName", task.getAgentName());
            event.put("status", task.getStatus() != null ? task.getStatus().name() : null);
            event.put("retryCount", task.getRetryCount());
            event.put("checkpointRef", task.getCheckpointRef());
            event.put("details", details != null ? details : Map.of());

            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            writeEventLine(taskEventPath(task), line);
            writeEventLine(projectEventPath(task), line);
        } catch (Exception e) {
            log.warn("Failed to append task event for task {}: {}", task.getId(), e.getMessage(), e);
        }
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        return value != null ? clamp(value, min, max) : fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<Map<String, Object>> readTaskEvents(Task task) {
        Path path = taskEventPath(task);
        if (!Files.exists(path)) {
            return List.of();
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int fromIndex = Math.max(0, lines.size() - MAX_TASK_EVENTS);
            List<Map<String, Object>> events = new ArrayList<>();
            for (String line : lines.subList(fromIndex, lines.size())) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    events.add(objectMapper.readValue(trimmed, new TypeReference<>() {}));
                } catch (Exception e) {
                    log.debug("Skipping malformed task event line for task {}: {}", task.getId(), e.getMessage());
                }
            }
            return events;
        } catch (IOException e) {
            log.warn("Failed to read task events for task {}: {}", task.getId(), e.getMessage(), e);
            return List.of();
        }
    }

    private Map<String, Object> readTaskEventTail(Task task, int tailLines) {
        Path path = taskEventPath(task);
        int requestedLines = clamp(tailLines, 1, MAX_TASK_LOG_TAIL_LINES, DEFAULT_TASK_LOG_TAIL_LINES);
        Map<String, Object> tail = new LinkedHashMap<>();
        tail.put("path", taskEventLogRef(task));
        tail.put("requestedLines", requestedLines);
        tail.put("exists", Files.exists(path));

        if (!Files.exists(path)) {
            tail.put("totalLines", 0);
            tail.put("startLine", 0);
            tail.put("lineCount", 0);
            tail.put("truncated", false);
            tail.put("lines", List.of());
            return tail;
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int fromIndex = Math.max(0, lines.size() - requestedLines);
            List<String> rawLines = lines.subList(fromIndex, lines.size()).stream()
                .map(String::stripTrailing)
                .toList();
            tail.put("totalLines", lines.size());
            tail.put("startLine", rawLines.isEmpty() ? 0 : fromIndex + 1);
            tail.put("lineCount", rawLines.size());
            tail.put("truncated", fromIndex > 0);
            tail.put("lines", rawLines);
            return tail;
        } catch (IOException e) {
            log.warn("Failed to read task event tail for task {}: {}", task.getId(), e.getMessage(), e);
            tail.put("totalLines", 0);
            tail.put("startLine", 0);
            tail.put("lineCount", 0);
            tail.put("truncated", false);
            tail.put("error", e.getMessage());
            tail.put("lines", List.of());
            return tail;
        }
    }

    private void writeEventLine(Path path, String line) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
            path,
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private String taskEventLogRef(Task task) {
        return "logs/tasks/" + task.getId() + ".jsonl";
    }

    private Path taskEventPath(Task task) {
        return projectRoot(task.getProjectId()).resolve(taskEventLogRef(task));
    }

    private Path projectEventPath(Task task) {
        return projectRoot(task.getProjectId()).resolve(TASK_EVENT_LOG);
    }

    public Map<String, Object> getTaskProgress(Task task) {
        Map<String, Object> fromCheckpoint = progressFromCheckpoint(task);
        if (!fromCheckpoint.isEmpty()) {
            return fromCheckpoint;
        }

        Map<String, Object> fromResult = progressFromResult(task);
        if (!fromResult.isEmpty()) {
            return fromResult;
        }

        Map<String, Object> fromMetrics = progressFromMap(task.getMetrics(), "metrics");
        if (!fromMetrics.isEmpty()) {
            return fromMetrics;
        }

        return progressFromStatus(task);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> progressFromCheckpoint(Task task) {
        String checkpointRef = task.getCheckpointRef();
        if (checkpointRef == null || checkpointRef.isBlank()) {
            checkpointRef = latestCheckpointRef(task);
        }
        if (checkpointRef == null || checkpointRef.isBlank()) {
            return Map.of();
        }

        try {
            Path checkpointPath = resolveProjectPath(task.getProjectId(), checkpointRef);
            if (!Files.exists(checkpointPath)) {
                return Map.of();
            }

            Map<String, Object> payload = objectMapper.readValue(
                checkpointPath.toFile(),
                new TypeReference<>() {}
            );
            Object stateValue = payload.get("state");
            Map<String, Object> state = stateValue instanceof Map<?, ?> stateMap
                ? new HashMap<>((Map<String, Object>) stateMap)
                : payload;

            Integer total = toInteger(firstValue(state, "total_chunks", "total_steps", "total_nodes", "total_items"));
            Integer processed = toInteger(firstValue(
                state,
                "analyzed_chunks",
                "processed_chunks",
                "processed_steps",
                "completed_nodes",
                "completed_items"
            ));
            if (processed == null && state.get("completed_chunk_ids") instanceof Collection<?> completed) {
                processed = completed.size();
            }
            Integer failed = null;
            if (state.get("failed_chunk_ids") instanceof Collection<?> failedChunks) {
                failed = failedChunks.size();
            }

            if (total != null && total > 0 && processed != null) {
                Map<String, Object> progress = progressMap(
                    "checkpoint",
                    processed,
                    total,
                    failed,
                    task.getStatus(),
                    checkpointRef
                );
                applyProgressMetadata(progress, state);
                return progress;
            }

            Map<String, Object> progress = progressFromMap(state, "checkpoint");
            if (!progress.isEmpty()) {
                progress.put("checkpointRef", checkpointRef);
                applyProgressMetadata(progress, state);
                return progress;
            }
        } catch (Exception e) {
            log.debug("Unable to read progress checkpoint for task {}: {}", task.getId(), e.getMessage());
        }
        return Map.of();
    }

    private Map<String, Object> progressFromResult(Task task) {
        Map<String, Object> result = task.getResult();
        if (result == null || result.isEmpty()) {
            return Map.of();
        }

        Integer total = toInteger(firstValue(result, "total_chunks", "total_nodes", "total_items"));
        Integer processed = toInteger(firstValue(result, "analyzed_chunks", "processed_chunks", "completed_nodes", "completed_items"));
        Integer failed = toInteger(firstValue(result, "failed_chunks", "failed_nodes", "failed_items"));
        if (total != null && total > 0 && processed != null) {
            return progressMap("result", processed, total, failed, task.getStatus(), task.getCheckpointRef());
        }

        return progressFromMap(result, "result");
    }

    private Map<String, Object> progressFromMap(Map<String, Object> values, String source) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Object value = firstValue(values, "progress", "progress_percent", "percent", "completion_ratio");
        Double ratio = toRatio(value);
        if (ratio == null) {
            return Map.of();
        }

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("source", source);
        progress.put("ratio", ratio);
        progress.put("percent", (int) Math.round(ratio * 100.0));
        progress.put("label", progress.get("percent") + "%");
        return progress;
    }

    private String latestCheckpointRef(Task task) {
        Path checkpointDir = projectRoot(task.getProjectId()).resolve("checkpoints");
        if (!Files.isDirectory(checkpointDir)) {
            return null;
        }

        try (var stream = Files.list(checkpointDir)) {
            return stream
                .filter(path -> Files.isRegularFile(path))
                .filter(path -> path.getFileName().toString().startsWith(task.getId() + "_"))
                .filter(path -> path.getFileName().toString().endsWith("_latest.json"))
                .max((left, right) -> Long.compare(lastModifiedMillis(left), lastModifiedMillis(right)))
                .map(Path::toString)
                .orElse(null);
        } catch (IOException e) {
            log.debug("Unable to locate latest checkpoint for task {}: {}", task.getId(), e.getMessage());
            return null;
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void applyProgressMetadata(Map<String, Object> progress, Map<String, Object> state) {
        Object stage = firstValue(state, "stage", "current_stage", "status");
        Object stageLabel = firstValue(state, "stage_label", "stageLabel", "current_stage_label");
        Object unit = firstValue(state, "progress_unit", "unit");
        if (stage != null) {
            progress.put("stage", stage);
        }
        if (stageLabel != null) {
            progress.put("stageLabel", stageLabel);
        }
        if (unit != null) {
            progress.put("unit", unit);
        }
        progress.put("label", progressLabel(progress));
    }

    private Map<String, Object> progressFromStatus(Task task) {
        double ratio = switch (task.getStatus()) {
            case SUCCESS, FAILED, CANCELLED -> 1.0;
            case PARTIAL -> 0.5;
            case RUNNING, PENDING -> 0.0;
        };

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("source", "status");
        progress.put("ratio", ratio);
        progress.put("percent", (int) Math.round(ratio * 100.0));
        progress.put("label", task.getStatus().name());
        return progress;
    }

    private String progressLabel(Map<String, Object> progress) {
        Object stageLabel = progress.get("stageLabel");
        Object processed = progress.get("processed");
        Object total = progress.get("total");
        Object unit = progress.get("unit");
        if (stageLabel != null && processed != null && total != null) {
            return stageLabel + " (" + processed + "/" + total + " " + progressUnitLabel(unit) + ")";
        }
        if (stageLabel != null) {
            return stageLabel + " " + progress.get("percent") + "%";
        }
        return progress.get("label") != null ? progress.get("label").toString() : progress.get("percent") + "%";
    }

    private String progressUnitLabel(Object unit) {
        if (unit == null) {
            return "chunks";
        }
        return switch (unit.toString()) {
            case "steps" -> "steps";
            case "nodes" -> "nodes";
            case "items" -> "items";
            default -> "chunks";
        };
    }

    private Map<String, Object> progressMap(
            String source,
            Integer processed,
            Integer total,
            Integer failed,
            TaskStatus status,
            String checkpointRef) {
        int boundedProcessed = Math.max(0, Math.min(processed, total));
        double ratio = total > 0 ? (double) boundedProcessed / (double) total : 0.0;
        if ((status == TaskStatus.SUCCESS || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED)
                && boundedProcessed >= total) {
            ratio = 1.0;
        }

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("source", source);
        progress.put("ratio", ratio);
        progress.put("percent", (int) Math.round(ratio * 100.0));
        progress.put("processed", boundedProcessed);
        progress.put("total", total);
        progress.put("failed", failed != null ? failed : 0);
        progress.put("label", boundedProcessed + "/" + total + " chunks");
        if (checkpointRef != null && !checkpointRef.isBlank()) {
            progress.put("checkpointRef", checkpointRef);
        }
        return progress;
    }

    private Path resolveProjectPath(String projectId, String path) {
        Path candidate = Path.of(path);
        if (!candidate.isAbsolute()) {
            candidate = projectRoot(projectId).resolve(path);
        }
        Path resolved = candidate.normalize().toAbsolutePath();
        Path root = projectRoot(projectId).normalize().toAbsolutePath();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Task checkpoint must stay inside project workspace");
        }
        return resolved;
    }

    private Path projectRoot(String projectId) {
        return Path.of(basePath, "projects", projectId);
    }

    private Object firstValue(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private Double toRatio(Object value) {
        if (value == null) {
            return null;
        }
        Double number;
        if (value instanceof Number numeric) {
            number = numeric.doubleValue();
        } else {
            try {
                number = Double.parseDouble(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (number < 0) {
            return null;
        }
        return number > 1.0 ? Math.min(1.0, number / 100.0) : Math.min(1.0, number);
    }
}
