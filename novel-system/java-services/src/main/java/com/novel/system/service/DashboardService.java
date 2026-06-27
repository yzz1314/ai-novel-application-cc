package com.novel.system.service;

import com.novel.system.dto.response.ProjectResponse;
import com.novel.system.dto.response.TaskResponse;
import com.novel.system.entity.DashboardAlertState;
import com.novel.system.entity.DashboardAlertState.AlertStatus;
import com.novel.system.entity.Project;
import com.novel.system.entity.Project.ProjectStatus;
import com.novel.system.entity.Sample.SampleStatus;
import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.repository.ChapterArtifactRepository;
import com.novel.system.repository.DashboardAlertStateRepository;
import com.novel.system.repository.GraphArtifactRepository;
import com.novel.system.repository.MemoryArtifactRepository;
import com.novel.system.repository.OutlineArtifactRepository;
import com.novel.system.repository.ProjectRepository;
import com.novel.system.repository.RetrievalArtifactRepository;
import com.novel.system.repository.SampleRepository;
import com.novel.system.repository.SkillProfileRepository;
import com.novel.system.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final long SLOW_TASK_THRESHOLD_MS = 10 * 60 * 1000L;
    private static final int HIGH_RETRY_THRESHOLD = 2;
    private static final int MONITORED_TASK_LIMIT = 50;
    private static final int RECENT_TASK_LIMIT = 8;

    private final ProjectRepository projectRepository;
    private final SampleRepository sampleRepository;
    private final TaskRepository taskRepository;
    private final DashboardAlertStateRepository dashboardAlertStateRepository;
    private final ChapterArtifactRepository chapterArtifactRepository;
    private final SkillProfileRepository skillProfileRepository;
    private final OutlineArtifactRepository outlineArtifactRepository;
    private final MemoryArtifactRepository memoryArtifactRepository;
    private final GraphArtifactRepository graphArtifactRepository;
    private final RetrievalArtifactRepository retrievalArtifactRepository;
    private final PythonClientService pythonClientService;
    private final TaskExecutorService taskExecutorService;

    public Map<String, Object> getDashboard() {
        List<Project> projects = projectRepository.findAll();
        List<Project> recentProjects = projects.stream()
            .sorted((left, right) -> nullSafeTime(right.getUpdatedAt()).compareTo(nullSafeTime(left.getUpdatedAt())))
            .limit(6)
            .toList();
        List<Task> monitoredTasks = taskRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, MONITORED_TASK_LIMIT));
        List<Task> recentTasks = monitoredTasks.stream().limit(RECENT_TASK_LIMIT).toList();
        Map<String, Object> stats = stats();
        Map<String, Object> taskSummary = taskSummary();
        List<Task> partialTasks = taskRepository.findByStatus(TaskStatus.PARTIAL);
        Map<String, Object> serviceStatus = serviceStatus();
        Map<String, Object> healthSummary = healthSummary(stats, taskSummary, partialTasks, serviceStatus);
        Map<String, Object> performanceSummary = performanceSummary(monitoredTasks);
        List<Map<String, Object>> blockedProjects = blockedProjects(projects);
        Map<String, Object> alertSummary = alertSummary(alerts(healthSummary, performanceSummary, blockedProjects, serviceStatus));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", LocalDateTime.now());
        response.put("stats", stats);
        response.put("taskSummary", taskSummary);
        response.put("healthSummary", healthSummary);
        response.put("performanceSummary", performanceSummary);
        response.put("alerts", alertSummary.get("activeAlerts"));
        response.put("snoozedAlerts", alertSummary.get("snoozedAlerts"));
        response.put("acknowledgedAlerts", alertSummary.get("acknowledgedAlerts"));
        response.put("alertSummary", alertSummary.get("summary"));
        response.put("workflowSummary", workflowSummary(recentProjects));
        response.put("blockedProjects", blockedProjects);
        response.put("nextActions", nextActions(stats, taskSummary, partialTasks, serviceStatus));
        response.put("recentProjects", recentProjects.stream().map(this::projectCard).toList());
        response.put("recentTasks", recentTasks.stream()
            .map(task -> TaskResponse.from(task, taskExecutorService.getTaskProgress(task)))
            .toList());
        response.put("serviceStatus", serviceStatus);
        return response;
    }

    public Map<String, Object> updateAlertState(String alertId, Map<String, Object> request) {
        String action = stringValue(request == null ? null : request.get("action"), "acknowledge").toLowerCase();
        String actor = stringValue(request == null ? null : request.get("actor"), "local-user");
        String note = stringValue(request == null ? null : request.get("note"), "");
        String conditionKey = stringValue(request == null ? null : request.get("conditionKey"), "");
        DashboardAlertState state = dashboardAlertStateRepository
            .findById(alertId)
            .orElseGet(() -> {
                DashboardAlertState created = new DashboardAlertState();
                created.setAlertId(alertId);
                return created;
            });

        if ("snooze".equals(action) || "snoozed".equals(action)) {
            long minutes = numberValue(request == null ? null : request.get("minutes"));
            minutes = minutes > 0 ? Math.min(minutes, 7 * 24 * 60) : 60;
            state.setStatus(AlertStatus.SNOOZED);
            state.setAcknowledgedAt(null);
            state.setSnoozedUntil(LocalDateTime.now().plusMinutes(minutes));
            state.setMetadata(details("action", "snooze", "minutes", minutes, "conditionKey", conditionKey));
        } else if ("reopen".equals(action) || "open".equals(action)) {
            state.setStatus(AlertStatus.OPEN);
            state.setAcknowledgedAt(null);
            state.setSnoozedUntil(null);
            state.setMetadata(details("action", "reopen", "conditionKey", conditionKey));
        } else {
            state.setStatus(AlertStatus.ACKNOWLEDGED);
            state.setAcknowledgedAt(LocalDateTime.now());
            state.setSnoozedUntil(null);
            state.setMetadata(details("action", "acknowledge", "conditionKey", conditionKey));
        }
        state.setActor(actor);
        state.setNote(note);

        DashboardAlertState saved = dashboardAlertStateRepository.save(state);
        Map<String, Object> response = stateMap(saved);
        response.put("alertId", alertId);
        response.put("action", action);
        return response;
    }

    private Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProjects", projectRepository.count());
        stats.put("activeProjects", projectRepository.findByStatusIn(List.of(
            ProjectStatus.CREATED,
            ProjectStatus.INGESTING,
            ProjectStatus.ANALYZED,
            ProjectStatus.OUTLINING,
            ProjectStatus.WRITING
        )).size());
        stats.put("archivedProjects", projectRepository.findByStatus(ProjectStatus.ARCHIVED).size());
        stats.put("totalSamples", sampleRepository.count());
        stats.put("analyzedSamples", sampleRepository.countByStatus(SampleStatus.ANALYZED));
        stats.put("totalChapters", chapterArtifactRepository.count());
        stats.put("draftChapters", chapterArtifactRepository.countByStage("draft"));
        stats.put("finalChapters", chapterArtifactRepository.countByStage("final"));
        stats.put("totalTasks", taskRepository.count());
        return stats;
    }

    private Map<String, Object> taskSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            summary.put(status.name(), taskRepository.countByStatus(status));
        }
        return summary;
    }

    private Map<String, Object> performanceSummary(List<Task> tasks) {
        long completedTaskCount = 0;
        long totalDurationMs = 0;
        long maxDurationMs = 0;
        String slowestTaskId = null;
        long slowTaskCount = 0;
        long highRetryTaskCount = 0;
        long totalLlmCalls = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long totalTokens = 0;
        Map<String, Object> byTaskType = new LinkedHashMap<>();

        for (Task task : tasks) {
            long durationMs = taskDurationMs(task);
            if (durationMs > 0) {
                completedTaskCount += 1;
                totalDurationMs += durationMs;
                if (durationMs > maxDurationMs) {
                    maxDurationMs = durationMs;
                    slowestTaskId = task.getId();
                }
                if (durationMs >= SLOW_TASK_THRESHOLD_MS) {
                    slowTaskCount += 1;
                }
            }

            if (task.getRetryCount() != null && task.getRetryCount() >= HIGH_RETRY_THRESHOLD) {
                highRetryTaskCount += 1;
            }

            long llmCalls = metricLong(task.getMetrics(), "llm_calls", "llmCalls");
            long inputTokens = metricLong(task.getMetrics(), "input_tokens", "inputTokens", "prompt_tokens", "promptTokens");
            long outputTokens = metricLong(task.getMetrics(), "output_tokens", "outputTokens", "completion_tokens", "completionTokens");
            long taskTokens = metricLong(task.getMetrics(), "total_tokens", "totalTokens");
            if (taskTokens == 0) {
                taskTokens = inputTokens + outputTokens;
            }
            totalLlmCalls += llmCalls;
            totalInputTokens += inputTokens;
            totalOutputTokens += outputTokens;
            totalTokens += taskTokens;

            mergeTaskTypeMetrics(byTaskType, task, durationMs, llmCalls, taskTokens);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("windowTaskCount", tasks.size());
        summary.put("completedTaskCount", completedTaskCount);
        summary.put("avgDurationMs", completedTaskCount > 0 ? Math.round((double) totalDurationMs / completedTaskCount) : 0L);
        summary.put("maxDurationMs", maxDurationMs);
        summary.put("slowestTaskId", slowestTaskId);
        summary.put("slowTaskThresholdMs", SLOW_TASK_THRESHOLD_MS);
        summary.put("slowTaskCount", slowTaskCount);
        summary.put("highRetryThreshold", HIGH_RETRY_THRESHOLD);
        summary.put("highRetryTaskCount", highRetryTaskCount);
        summary.put("totalLlmCalls", totalLlmCalls);
        summary.put("totalInputTokens", totalInputTokens);
        summary.put("totalOutputTokens", totalOutputTokens);
        summary.put("totalTokens", totalTokens);
        summary.put("avgTokensPerLlmCall", totalLlmCalls > 0 ? Math.round((double) totalTokens / totalLlmCalls) : 0L);
        summary.put("byTaskType", byTaskType.values().stream().toList());
        return summary;
    }

    @SuppressWarnings("unchecked")
    private void mergeTaskTypeMetrics(
            Map<String, Object> byTaskType,
            Task task,
            long durationMs,
            long llmCalls,
            long tokens) {
        String taskType = task.getTaskType() != null ? task.getTaskType() : "unknown";
        Map<String, Object> item = (Map<String, Object>) byTaskType.computeIfAbsent(taskType, key -> {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("taskType", key);
            created.put("taskCount", 0L);
            created.put("completedTaskCount", 0L);
            created.put("totalDurationMs", 0L);
            created.put("avgDurationMs", 0L);
            created.put("totalLlmCalls", 0L);
            created.put("totalTokens", 0L);
            return created;
        });
        item.put("taskCount", numberValue(item.get("taskCount")) + 1);
        item.put("totalLlmCalls", numberValue(item.get("totalLlmCalls")) + llmCalls);
        item.put("totalTokens", numberValue(item.get("totalTokens")) + tokens);
        if (durationMs > 0) {
            long completed = numberValue(item.get("completedTaskCount")) + 1;
            long totalDuration = numberValue(item.get("totalDurationMs")) + durationMs;
            item.put("completedTaskCount", completed);
            item.put("totalDurationMs", totalDuration);
            item.put("avgDurationMs", Math.round((double) totalDuration / completed));
        }
    }

    private Map<String, Object> healthSummary(
            Map<String, Object> stats,
            Map<String, Object> taskSummary,
            List<Task> partialTasks,
            Map<String, Object> serviceStatus) {
        long activeTasks = taskCount(taskSummary, TaskStatus.PENDING) + taskCount(taskSummary, TaskStatus.RUNNING);
        long failedTasks = taskCount(taskSummary, TaskStatus.FAILED);
        long waitingApprovals = partialTasks.stream().filter(this::isWaitingForHuman).count();
        long completedTasks = taskCount(taskSummary, TaskStatus.SUCCESS)
            + taskCount(taskSummary, TaskStatus.PARTIAL)
            + taskCount(taskSummary, TaskStatus.FAILED)
            + taskCount(taskSummary, TaskStatus.CANCELLED);
        long successfulTasks = taskCount(taskSummary, TaskStatus.SUCCESS);
        int successRate = completedTasks > 0 ? Math.round(successfulTasks * 100f / completedTasks) : 0;
        boolean pythonUp = serviceIsUp(serviceStatus.get("python"));

        String status;
        String message;
        if (!pythonUp) {
            status = "DEGRADED";
            message = "Python AI 服务不可用，新的 Agent 任务可能失败";
        } else if (failedTasks > 0) {
            status = "ATTENTION";
            message = "存在失败任务，建议优先查看诊断并重试";
        } else if (waitingApprovals > 0) {
            status = "WAITING_APPROVAL";
            message = "有工作流等待人工确认";
        } else if (activeTasks > 0) {
            status = "BUSY";
            message = "任务正在运行，生产线处于工作中";
        } else {
            status = "HEALTHY";
            message = "服务正常，暂无阻塞任务";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("message", message);
        summary.put("activeTasks", activeTasks);
        summary.put("failedTasks", failedTasks);
        summary.put("waitingApprovals", waitingApprovals);
        summary.put("partialTasks", taskCount(taskSummary, TaskStatus.PARTIAL));
        summary.put("successRate", successRate);
        summary.put("activeProjects", stats.get("activeProjects"));
        summary.put("generatedAt", LocalDateTime.now());
        return summary;
    }

    private List<Map<String, Object>> workflowSummary(List<Project> projects) {
        return projects.stream()
            .map(project -> {
                String projectId = project.getId();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("project", ProjectResponse.from(project));
                item.put("samples", sampleRepository.countByProjectId(projectId));
                item.put("analyzedSamples", sampleRepository.countByProjectIdAndStatus(projectId, SampleStatus.ANALYZED));
                item.put("skills", skillProfileRepository.countByProjectId(projectId));
                item.put("outlines", outlineArtifactRepository.countByProjectId(projectId));
                item.put("draftChapters", chapterArtifactRepository.countByProjectIdAndStage(projectId, "draft"));
                item.put("finalChapters", chapterArtifactRepository.countByProjectIdAndStage(projectId, "final"));
                item.put("memoryArtifacts", memoryArtifactRepository.countByProjectId(projectId));
                item.put("graphArtifacts", graphArtifactRepository.countByProjectId(projectId));
                item.put("retrievalArtifacts", retrievalArtifactRepository.countByProjectId(projectId));
                item.put("tasks", taskRepository.countByProjectId(projectId));
                item.put("failedTasks", taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.FAILED));
                item.put("progress", projectProgress(item));
                return item;
            })
            .toList();
    }

    private Map<String, Object> projectCard(Project project) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("project", ProjectResponse.from(project));
        card.put("sampleCount", sampleRepository.countByProjectId(project.getId()));
        card.put("chapterCount", chapterArtifactRepository.countByProjectId(project.getId()));
        card.put("taskCount", taskRepository.countByProjectId(project.getId()));
        card.put("failedTaskCount", taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.FAILED));
        return card;
    }

    private List<Map<String, Object>> blockedProjects(List<Project> projects) {
        return projects.stream()
            .map(this::projectBlocker)
            .filter(item -> item != null)
            .limit(6)
            .toList();
    }

    private Map<String, Object> projectBlocker(Project project) {
        long failedTasks = taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.FAILED);
        long partialTasks = taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.PARTIAL);
        List<Task> recentPartialTasks = taskRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(
            project.getId(),
            TaskStatus.PARTIAL,
            PageRequest.of(0, 20)
        );
        long waitingApprovals = recentPartialTasks.stream().filter(this::isWaitingForHuman).count();
        if (failedTasks == 0 && waitingApprovals == 0 && partialTasks == 0) {
            return null;
        }

        List<Task> recentTasks = taskRepository.findByProjectIdOrderByCreatedAtDesc(
            project.getId(),
            PageRequest.of(0, 1)
        );
        String blocker = failedTasks > 0
            ? "FAILED_TASK"
            : waitingApprovals > 0 ? "WAITING_APPROVAL" : "PARTIAL_TASK";

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("project", ProjectResponse.from(project));
        item.put("blocker", blocker);
        item.put("failedTasks", failedTasks);
        item.put("partialTasks", partialTasks);
        item.put("waitingApprovals", waitingApprovals);
        item.put("latestTask", recentTasks.isEmpty()
            ? null
            : TaskResponse.from(recentTasks.get(0), taskExecutorService.getTaskProgress(recentTasks.get(0))));
        return item;
    }

    private List<Map<String, Object>> nextActions(
            Map<String, Object> stats,
            Map<String, Object> taskSummary,
            List<Task> partialTasks,
            Map<String, Object> serviceStatus) {
        List<Map<String, Object>> actions = new ArrayList<>();
        long failedTasks = taskCount(taskSummary, TaskStatus.FAILED);
        long activeTasks = taskCount(taskSummary, TaskStatus.PENDING) + taskCount(taskSummary, TaskStatus.RUNNING);
        long waitingApprovals = partialTasks.stream().filter(this::isWaitingForHuman).count();

        if (!serviceIsUp(serviceStatus.get("python"))) {
            actions.add(action("检查 Python 服务", "python-service", "Python AI 服务当前不可用，先恢复服务再启动新任务"));
        }
        if (failedTasks > 0) {
            actions.add(action("处理失败任务", "tasks", "进入任务中心查看错误、日志和 checkpoint 后重试"));
        }
        if (waitingApprovals > 0) {
            actions.add(action("审批工作流", "tasks", "有工作流停在人工确认节点，批准后才能继续执行"));
        }
        if (numberValue(stats.get("totalProjects")) == 0) {
            actions.add(action("创建项目", "projects", "创建第一个长篇项目并上传样本"));
        }
        if (activeTasks > 0) {
            actions.add(action("查看运行任务", "tasks", "跟踪当前运行或排队任务的进度"));
        }
        if (actions.isEmpty()) {
            actions.add(action("推进最近项目", "projects", "从最近项目进入样本分析、Skill、大纲或章节生产"));
        }
        return actions.stream().limit(4).toList();
    }

    private List<Map<String, Object>> alerts(
            Map<String, Object> healthSummary,
            Map<String, Object> performanceSummary,
            List<Map<String, Object>> blockedProjects,
            Map<String, Object> serviceStatus) {
        List<Map<String, Object>> alerts = new ArrayList<>();

        if (!serviceIsUp(serviceStatus.get("python"))) {
            alerts.add(alert(
                "python_down",
                "critical",
                "Python AI 服务不可用",
                "新的 Agent 任务可能失败；请优先恢复 python-service。",
                "python-service",
                details("service", serviceStatus.get("python"))
            ));
        }

        long failedTasks = numberValue(healthSummary.get("failedTasks"));
        if (failedTasks > 0) {
            alerts.add(alert(
                "failed_tasks",
                "critical",
                "存在失败任务",
                "当前有 " + failedTasks + " 个失败任务，需要查看诊断日志并重试或修复输入。",
                "tasks",
                details("failedTasks", failedTasks)
            ));
        }

        long waitingApprovals = numberValue(healthSummary.get("waitingApprovals"));
        if (waitingApprovals > 0) {
            alerts.add(alert(
                "waiting_approvals",
                "warning",
                "工作流等待人工确认",
                "当前有 " + waitingApprovals + " 个任务停在人工确认节点。",
                "tasks",
                details("waitingApprovals", waitingApprovals)
            ));
        }

        long slowTaskCount = numberValue(performanceSummary.get("slowTaskCount"));
        if (slowTaskCount > 0) {
            alerts.add(alert(
                "slow_tasks",
                "warning",
                "存在慢任务",
                "最近任务窗口中有 " + slowTaskCount + " 个任务超过慢任务阈值。",
                "tasks",
                details(
                    "slowTaskCount", slowTaskCount,
                    "thresholdMs", performanceSummary.get("slowTaskThresholdMs"),
                    "slowestTaskId", performanceSummary.get("slowestTaskId")
                )
            ));
        }

        long highRetryTaskCount = numberValue(performanceSummary.get("highRetryTaskCount"));
        if (highRetryTaskCount > 0) {
            alerts.add(alert(
                "high_retry_tasks",
                "warning",
                "存在高重试任务",
                "最近任务窗口中有 " + highRetryTaskCount + " 个任务达到高重试阈值。",
                "tasks",
                details(
                    "highRetryTaskCount", highRetryTaskCount,
                    "threshold", performanceSummary.get("highRetryThreshold")
                )
            ));
        }

        if (!blockedProjects.isEmpty()) {
            alerts.add(alert(
                "blocked_projects",
                "warning",
                "存在阻塞项目",
                "当前有 " + blockedProjects.size() + " 个项目存在失败、部分完成或待审批任务。",
                "projects",
                details("blockedProjectCount", blockedProjects.size())
            ));
        }

        return alerts;
    }

    private Map<String, Object> alertSummary(List<Map<String, Object>> generatedAlerts) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, DashboardAlertState> states = alertStates(generatedAlerts.stream()
            .map(alert -> String.valueOf(alert.get("id")))
            .toList());
        List<Map<String, Object>> activeAlerts = new ArrayList<>();
        List<Map<String, Object>> snoozedAlerts = new ArrayList<>();
        List<Map<String, Object>> acknowledgedAlerts = new ArrayList<>();

        for (Map<String, Object> alert : generatedAlerts) {
            DashboardAlertState state = states.get(String.valueOf(alert.get("id")));
            if (!matchesCondition(state, alert)) {
                state = null;
            }
            Map<String, Object> enriched = new LinkedHashMap<>(alert);
            enriched.put("state", stateMap(state, now));
            if (isSnoozed(state, now)) {
                snoozedAlerts.add(enriched);
            } else if (state != null && state.getStatus() == AlertStatus.ACKNOWLEDGED) {
                acknowledgedAlerts.add(enriched);
            } else {
                activeAlerts.add(enriched);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activeCount", activeAlerts.size());
        summary.put("snoozedCount", snoozedAlerts.size());
        summary.put("acknowledgedCount", acknowledgedAlerts.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeAlerts", activeAlerts);
        result.put("snoozedAlerts", snoozedAlerts);
        result.put("acknowledgedAlerts", acknowledgedAlerts);
        result.put("summary", summary);
        return result;
    }

    private Map<String, DashboardAlertState> alertStates(Collection<String> alertIds) {
        if (alertIds == null || alertIds.isEmpty()) {
            return Map.of();
        }
        return dashboardAlertStateRepository.findByAlertIdIn(alertIds).stream()
            .collect(Collectors.toMap(DashboardAlertState::getAlertId, Function.identity(), (left, right) -> left));
    }

    private boolean isSnoozed(DashboardAlertState state, LocalDateTime now) {
        return state != null
            && state.getStatus() == AlertStatus.SNOOZED
            && state.getSnoozedUntil() != null
            && state.getSnoozedUntil().isAfter(now);
    }

    private boolean matchesCondition(DashboardAlertState state, Map<String, Object> alert) {
        if (state == null || state.getMetadata() == null) {
            return false;
        }
        Object stateConditionKey = state.getMetadata().get("conditionKey");
        Object alertConditionKey = alert.get("conditionKey");
        return stateConditionKey != null && stateConditionKey.equals(alertConditionKey);
    }

    private Map<String, Object> stateMap(DashboardAlertState state) {
        return stateMap(state, LocalDateTime.now());
    }

    private Map<String, Object> stateMap(DashboardAlertState state, LocalDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (state == null) {
            result.put("status", AlertStatus.OPEN.name());
            return result;
        }
        AlertStatus status = state.getStatus() == null ? AlertStatus.OPEN : state.getStatus();
        if (status == AlertStatus.SNOOZED && !isSnoozed(state, now)) {
            result.put("previousStatus", status.name());
            result.put("expiredSnooze", true);
            status = AlertStatus.OPEN;
        }
        result.put("status", status.name());
        result.put("actor", state.getActor());
        result.put("note", state.getNote());
        result.put("acknowledgedAt", state.getAcknowledgedAt());
        result.put("snoozedUntil", state.getSnoozedUntil());
        result.put("updatedAt", state.getUpdatedAt());
        result.put("metadata", state.getMetadata() == null ? Map.of() : state.getMetadata());
        return result;
    }

    private Map<String, Object> details(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (keyValues == null) {
            return details;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                details.put(String.valueOf(key), value);
            }
        }
        return details;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private Map<String, Object> alert(
            String id,
            String severity,
            String title,
            String message,
            String target,
            Map<String, Object> details) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("id", id);
        alert.put("severity", severity);
        alert.put("title", title);
        alert.put("message", message);
        alert.put("target", target);
        alert.put("details", details != null ? details : Map.of());
        alert.put("conditionKey", id + ":" + Integer.toHexString((details != null ? details : Map.of()).hashCode()));
        alert.put("createdAt", LocalDateTime.now());
        return alert;
    }

    private Map<String, Object> action(String title, String target, String description) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("title", title);
        action.put("target", target);
        action.put("description", description);
        return action;
    }

    private int projectProgress(Map<String, Object> item) {
        int passed = 0;
        passed += countPositive(item.get("samples"));
        passed += countPositive(item.get("analyzedSamples"));
        passed += countPositive(item.get("skills"));
        passed += countPositive(item.get("outlines"));
        passed += countPositive(item.get("draftChapters"));
        passed += countPositive(item.get("finalChapters"));
        passed += countPositive(item.get("memoryArtifacts"));
        passed += countPositive(item.get("graphArtifacts"));
        passed += countPositive(item.get("retrievalArtifacts"));
        return Math.round(passed * 100f / 9f);
    }

    private int countPositive(Object value) {
        return value instanceof Number number && number.longValue() > 0 ? 1 : 0;
    }

    private long taskCount(Map<String, Object> summary, TaskStatus status) {
        return numberValue(summary.get(status.name()));
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private long taskDurationMs(Task task) {
        long durationMs = metricLong(task.getMetrics(), "duration_ms", "durationMs");
        if (durationMs > 0) {
            return durationMs;
        }

        long durationSeconds = metricLong(task.getMetrics(), "duration_seconds", "durationSeconds");
        if (durationSeconds > 0) {
            return durationSeconds * 1000L;
        }

        if (task.getStartedAt() != null && task.getFinishedAt() != null) {
            return Math.max(0L, Duration.between(task.getStartedAt(), task.getFinishedAt()).toMillis());
        }
        return 0L;
    }

    private long metricLong(Map<String, Object> metrics, String... keys) {
        if (metrics == null || metrics.isEmpty()) {
            return 0L;
        }
        for (String key : keys) {
            long value = numberValue(metrics.get(key));
            if (value != 0L) {
                return value;
            }
        }
        return 0L;
    }

    private boolean isWaitingForHuman(Task task) {
        Object waiting = task.getResult() != null ? task.getResult().get("waiting_for_human") : null;
        return waiting instanceof Map<?, ?> waitingMap && waitingMap.get("node_id") != null;
    }

    private boolean serviceIsUp(Object service) {
        return service instanceof Map<?, ?> map && "UP".equals(String.valueOf(map.get("status")));
    }

    private Map<String, Object> serviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("java", Map.of(
            "status", "UP",
            "service", "novel-system-java"
        ));
        boolean pythonHealthy = pythonClientService.checkHealth();
        status.put("python", Map.of(
            "status", pythonHealthy ? "UP" : "DOWN",
            "service", "python-ai-service"
        ));
        return status;
    }

    private LocalDateTime nullSafeTime(LocalDateTime value) {
        return value == null ? LocalDateTime.MIN : value;
    }
}
