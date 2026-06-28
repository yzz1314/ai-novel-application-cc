package com.novel.system.service;

import com.novel.system.entity.Project;
import com.novel.system.entity.DashboardAlertNotification;
import com.novel.system.entity.DashboardAlertState;
import com.novel.system.entity.DashboardMetricSnapshot;
import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.repository.ChapterArtifactRepository;
import com.novel.system.repository.DashboardAlertNotificationRepository;
import com.novel.system.repository.DashboardAlertStateRepository;
import com.novel.system.repository.DashboardMetricSnapshotRepository;
import com.novel.system.repository.GraphArtifactRepository;
import com.novel.system.repository.MemoryArtifactRepository;
import com.novel.system.repository.OutlineArtifactRepository;
import com.novel.system.repository.ProjectRepository;
import com.novel.system.repository.RetrievalArtifactRepository;
import com.novel.system.repository.SampleRepository;
import com.novel.system.repository.SkillProfileRepository;
import com.novel.system.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SampleRepository sampleRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private DashboardAlertNotificationRepository dashboardAlertNotificationRepository;
    @Mock
    private DashboardAlertStateRepository dashboardAlertStateRepository;
    @Mock
    private DashboardMetricSnapshotRepository dashboardMetricSnapshotRepository;
    @Mock
    private ChapterArtifactRepository chapterArtifactRepository;
    @Mock
    private SkillProfileRepository skillProfileRepository;
    @Mock
    private OutlineArtifactRepository outlineArtifactRepository;
    @Mock
    private MemoryArtifactRepository memoryArtifactRepository;
    @Mock
    private GraphArtifactRepository graphArtifactRepository;
    @Mock
    private RetrievalArtifactRepository retrievalArtifactRepository;
    @Mock
    private PythonClientService pythonClientService;
    @Mock
    private TaskExecutorService taskExecutorService;
    @Mock
    private Environment environment;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
            projectRepository,
            sampleRepository,
            taskRepository,
            dashboardAlertNotificationRepository,
            dashboardAlertStateRepository,
            dashboardMetricSnapshotRepository,
            chapterArtifactRepository,
            skillProfileRepository,
            outlineArtifactRepository,
            memoryArtifactRepository,
            graphArtifactRepository,
            retrievalArtifactRepository,
            pythonClientService,
            taskExecutorService,
            environment
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardHighlightsFailuresApprovalsAndBlockedProjects() {
        Project project = project("project_dashboard");
        Task failedTask = task("task_failed", TaskStatus.FAILED, null);
        failedTask.setMetrics(Map.of(
            "duration_ms", 700_000,
            "llm_calls", 2,
            "input_tokens", 100,
            "output_tokens", 50
        ));
        Task approvalTask = task("task_partial", TaskStatus.PARTIAL, Map.of(
            "waiting_for_human", Map.of("node_id", "approve_outline")
        ));
        approvalTask.setRetryCount(2);
        LocalDateTime finishedAt = LocalDateTime.now();
        approvalTask.setStartedAt(finishedAt.minusMinutes(2));
        approvalTask.setFinishedAt(finishedAt);

        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(projectRepository.count()).thenReturn(1L);
        when(projectRepository.findByStatusIn(any())).thenReturn(List.of(project));
        when(projectRepository.findByStatus(Project.ProjectStatus.ARCHIVED)).thenReturn(List.of());
        when(sampleRepository.count()).thenReturn(2L);
        when(sampleRepository.countByStatus(any())).thenReturn(1L);
        when(chapterArtifactRepository.count()).thenReturn(3L);
        when(chapterArtifactRepository.countByStage("draft")).thenReturn(2L);
        when(chapterArtifactRepository.countByStage("final")).thenReturn(1L);
        when(taskRepository.count()).thenReturn(4L);
        when(taskRepository.countByStatus(TaskStatus.PENDING)).thenReturn(0L);
        when(taskRepository.countByStatus(TaskStatus.RUNNING)).thenReturn(1L);
        when(taskRepository.countByStatus(TaskStatus.PARTIAL)).thenReturn(1L);
        when(taskRepository.countByStatus(TaskStatus.SUCCESS)).thenReturn(1L);
        when(taskRepository.countByStatus(TaskStatus.FAILED)).thenReturn(1L);
        when(taskRepository.countByStatus(TaskStatus.CANCELLED)).thenReturn(0L);
        when(taskRepository.findByStatus(TaskStatus.PARTIAL)).thenReturn(List.of(approvalTask));
        when(taskRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of(failedTask, approvalTask));
        when(dashboardAlertStateRepository.findByAlertIdIn(any())).thenReturn(List.of());
        mockNotificationPersistence();
        when(dashboardMetricSnapshotRepository.save(any(DashboardMetricSnapshot.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.countByProjectId(project.getId())).thenReturn(2L);
        when(taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.FAILED)).thenReturn(1L);
        when(taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.PARTIAL)).thenReturn(1L);
        when(taskRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(
            eq(project.getId()),
            eq(TaskStatus.PARTIAL),
            any(Pageable.class)
        )).thenReturn(List.of(approvalTask));
        when(taskRepository.findByProjectIdOrderByCreatedAtDesc(eq(project.getId()), any(Pageable.class)))
            .thenReturn(List.of(failedTask));
        when(sampleRepository.countByProjectId(project.getId())).thenReturn(2L);
        when(sampleRepository.countByProjectIdAndStatus(project.getId(), com.novel.system.entity.Sample.SampleStatus.ANALYZED))
            .thenReturn(1L);
        when(skillProfileRepository.countByProjectId(project.getId())).thenReturn(1L);
        when(outlineArtifactRepository.countByProjectId(project.getId())).thenReturn(1L);
        when(chapterArtifactRepository.countByProjectId(project.getId())).thenReturn(3L);
        when(chapterArtifactRepository.countByProjectIdAndStage(project.getId(), "draft")).thenReturn(2L);
        when(chapterArtifactRepository.countByProjectIdAndStage(project.getId(), "final")).thenReturn(1L);
        when(memoryArtifactRepository.countByProjectId(project.getId())).thenReturn(1L);
        when(graphArtifactRepository.countByProjectId(project.getId())).thenReturn(1L);
        when(retrievalArtifactRepository.countByProjectId(project.getId())).thenReturn(1L);
        when(pythonClientService.checkHealth()).thenReturn(true);
        when(taskExecutorService.getTaskProgress(any(Task.class))).thenReturn(Map.of("percent", 100, "label", "done"));

        Map<String, Object> dashboard = dashboardService.getDashboard();

        Map<String, Object> health = (Map<String, Object>) dashboard.get("healthSummary");
        assertThat(health.get("status")).isEqualTo("ATTENTION");
        assertThat(health.get("failedTasks")).isEqualTo(1L);
        assertThat(health.get("waitingApprovals")).isEqualTo(1L);
        assertThat(health.get("successRate")).isEqualTo(33);

        Map<String, Object> performance = (Map<String, Object>) dashboard.get("performanceSummary");
        assertThat(performance.get("windowTaskCount")).isEqualTo(2);
        assertThat(performance.get("completedTaskCount")).isEqualTo(2L);
        assertThat(performance.get("avgDurationMs")).isEqualTo(410_000L);
        assertThat(performance.get("maxDurationMs")).isEqualTo(700_000L);
        assertThat(performance.get("slowestTaskId")).isEqualTo("task_failed");
        assertThat(performance.get("slowTaskCount")).isEqualTo(1L);
        assertThat(performance.get("highRetryTaskCount")).isEqualTo(1L);
        assertThat(performance.get("totalLlmCalls")).isEqualTo(2L);
        assertThat(performance.get("totalTokens")).isEqualTo(150L);
        assertThat((List<Map<String, Object>>) performance.get("byTaskType"))
            .singleElement()
            .satisfies(item -> {
                assertThat(item.get("taskType")).isEqualTo("workflow");
                assertThat(item.get("taskCount")).isEqualTo(2L);
                assertThat(item.get("avgDurationMs")).isEqualTo(410_000L);
            });
        Map<String, Object> latestTrend = (Map<String, Object>) dashboard.get("latestTrend");
        assertThat(latestTrend)
            .containsEntry("healthStatus", "ATTENTION")
            .containsEntry("failedTasks", 1L)
            .containsEntry("activeAlertCount", 5L);
        Map<String, Object> notificationSummary = (Map<String, Object>) dashboard.get("alertNotificationSummary");
        assertThat(notificationSummary)
            .containsEntry("generated", 5)
            .containsEntry("pendingChannel", 5L)
            .containsEntry("escalated", 1L);
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) dashboard.get("alertNotifications");
        assertThat(notifications)
            .filteredOn(notification -> "failed_tasks".equals(notification.get("alertId")))
            .singleElement()
            .satisfies(notification -> {
                assertThat(notification.get("escalationLevel")).isEqualTo("ESCALATE");
                assertThat(notification.get("status")).isEqualTo("PENDING_CHANNEL");
            });

        List<Map<String, Object>> blockedProjects = (List<Map<String, Object>>) dashboard.get("blockedProjects");
        assertThat(blockedProjects).hasSize(1);
        assertThat(blockedProjects.get(0).get("blocker")).isEqualTo("FAILED_TASK");

        List<Map<String, Object>> alerts = (List<Map<String, Object>>) dashboard.get("alerts");
        assertThat(alerts)
            .extracting(alert -> alert.get("id"))
            .contains("failed_tasks", "waiting_approvals", "slow_tasks", "high_retry_tasks", "blocked_projects");
        assertThat(alerts)
            .filteredOn(alert -> "critical".equals(alert.get("severity")))
            .extracting(alert -> alert.get("target"))
            .contains("tasks");
        assertThat(alerts)
            .filteredOn(alert -> "failed_tasks".equals(alert.get("id")))
            .singleElement()
            .satisfies(alert -> {
                assertThat(alert.get("conditionKey")).isNotNull();
                assertThat((Map<String, Object>) alert.get("state")).containsEntry("status", "OPEN");
            });

        List<Map<String, Object>> nextActions = (List<Map<String, Object>>) dashboard.get("nextActions");
        assertThat(nextActions)
            .extracting(action -> action.get("target"))
            .contains("tasks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acknowledgedAlertOnlyHidesSameCondition() {
        Project project = project("project_dashboard");
        Task failedTask = task("task_failed", TaskStatus.FAILED, null);

        mockDashboardBasics(project, failedTask);
        mockNotificationPersistence();
        when(dashboardAlertStateRepository.findByAlertIdIn(any())).thenReturn(List.of(acknowledgedState(
            "failed_tasks",
            "failed_tasks:" + Integer.toHexString(Map.of("failedTasks", 1L).hashCode())
        )));

        Map<String, Object> dashboard = dashboardService.getDashboard();

        List<Map<String, Object>> alerts = (List<Map<String, Object>>) dashboard.get("alerts");
        List<Map<String, Object>> acknowledgedAlerts = (List<Map<String, Object>>) dashboard.get("acknowledgedAlerts");
        assertThat(alerts)
            .extracting(alert -> alert.get("id"))
            .doesNotContain("failed_tasks");
        assertThat(acknowledgedAlerts)
            .extracting(alert -> alert.get("id"))
            .contains("failed_tasks");

        when(dashboardAlertStateRepository.findByAlertIdIn(any())).thenReturn(List.of(acknowledgedState(
            "failed_tasks",
            "failed_tasks:" + Integer.toHexString(Map.of("failedTasks", 2L).hashCode())
        )));

        Map<String, Object> changedConditionDashboard = dashboardService.getDashboard();
        List<Map<String, Object>> changedConditionAlerts =
            (List<Map<String, Object>>) changedConditionDashboard.get("alerts");

        assertThat(changedConditionAlerts)
            .extracting(alert -> alert.get("id"))
            .contains("failed_tasks");
    }

    @Test
    void updateAlertStateCanSnoozeDashboardAlert() {
        when(dashboardAlertStateRepository.findById("failed_tasks")).thenReturn(Optional.empty());
        when(dashboardAlertStateRepository.save(argThat(state ->
            "failed_tasks".equals(state.getAlertId())
                && state.getStatus() == com.novel.system.entity.DashboardAlertState.AlertStatus.SNOOZED
                && state.getSnoozedUntil() != null
        ))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> state = dashboardService.updateAlertState("failed_tasks", Map.of(
            "action", "snooze",
            "minutes", 30,
            "actor", "tester",
            "note", "investigating"
        ));

        assertThat(state.get("status")).isEqualTo("SNOOZED");
        assertThat(state.get("actor")).isEqualTo("tester");
        assertThat(state.get("note")).isEqualTo("investigating");
        assertThat(state.get("snoozedUntil")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTrendsReturnsSnapshotDeltas() {
        DashboardMetricSnapshot older = snapshot("older", LocalDateTime.now().minusMinutes(20), "HEALTHY", 0, 0, 100);
        DashboardMetricSnapshot latest = snapshot("latest", LocalDateTime.now(), "ATTENTION", 2, 1, 250);
        when(dashboardMetricSnapshotRepository.findAllByOrderByCapturedAtDesc(any(Pageable.class)))
            .thenReturn(List.of(latest, older));

        Map<String, Object> trends = dashboardService.getTrends(500);

        assertThat(trends.get("limit")).isEqualTo(200);
        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) trends.get("snapshots");
        assertThat(snapshots)
            .extracting(snapshot -> snapshot.get("id"))
            .containsExactly("latest", "older");
        Map<String, Object> summary = (Map<String, Object>) trends.get("summary");
        assertThat(summary)
            .containsEntry("snapshotCount", 2)
            .containsEntry("failedTaskDelta", 2L)
            .containsEntry("activeAlertDelta", 1L)
            .containsEntry("totalTokenDelta", 150L)
            .containsEntry("latestHealthStatus", "ATTENTION");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAlertNotificationsReturnsRecentNotificationSummary() {
        DashboardAlertNotification notification = notification("notice_1", "failed_tasks", "ESCALATE", "READY");
        when(dashboardAlertNotificationRepository.findAllByOrderByLastSeenAtDesc(any(Pageable.class)))
            .thenReturn(List.of(notification));
        when(environment.getProperty("dashboard.alerts.webhook-url")).thenReturn("https://example.invalid/hook");

        Map<String, Object> response = dashboardService.getAlertNotifications(500);

        assertThat(response.get("limit")).isEqualTo(200);
        Map<String, Object> summary = (Map<String, Object>) response.get("summary");
        assertThat(summary)
            .containsEntry("total", 1)
            .containsEntry("ready", 1L)
            .containsEntry("escalated", 1L);
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) response.get("notifications");
        assertThat(notifications)
            .singleElement()
            .satisfies(item -> {
                assertThat(item.get("id")).isEqualTo("notice_1");
                assertThat(item.get("alertId")).isEqualTo("failed_tasks");
            });
        Map<String, Object> channels = (Map<String, Object>) response.get("channels");
        assertThat((Map<String, Object>) channels.get("webhook")).containsEntry("configured", true);
    }

    private Project project(String projectId) {
        Project project = new Project();
        project.setId(projectId);
        project.setName("Dashboard Project");
        project.setDescription("Dashboard test");
        project.setGenre("玄幻");
        project.setSampleGroupType(Project.SampleGroupType.SAME_GENRE);
        project.setStatus(Project.ProjectStatus.WRITING);
        project.setSourceLanguage("zh-CN");
        project.setTargetLanguage("zh-CN");
        project.setCreatedAt(LocalDateTime.now().minusDays(1));
        project.setUpdatedAt(LocalDateTime.now());
        return project;
    }

    private Task task(String taskId, TaskStatus status, Map<String, Object> result) {
        Task task = new Task();
        task.setId(taskId);
        task.setProjectId("project_dashboard");
        task.setTaskType("workflow");
        task.setAgentName("workflow");
        task.setStatus(status);
        task.setResult(result);
        task.setCreatedAt(LocalDateTime.now());
        task.setRetryCount(0);
        return task;
    }

    private DashboardAlertState acknowledgedState(String alertId, String conditionKey) {
        DashboardAlertState state = new DashboardAlertState();
        state.setAlertId(alertId);
        state.setStatus(DashboardAlertState.AlertStatus.ACKNOWLEDGED);
        state.setActor("tester");
        state.setAcknowledgedAt(LocalDateTime.now());
        state.setMetadata(Map.of("conditionKey", conditionKey));
        return state;
    }

    private DashboardMetricSnapshot snapshot(
            String id,
            LocalDateTime capturedAt,
            String status,
            long failedTasks,
            long activeAlerts,
            long totalTokens) {
        DashboardMetricSnapshot snapshot = new DashboardMetricSnapshot();
        snapshot.setId(id);
        snapshot.setCapturedAt(capturedAt);
        snapshot.setHealthStatus(status);
        snapshot.setActiveTasks(0L);
        snapshot.setFailedTasks(failedTasks);
        snapshot.setWaitingApprovals(0L);
        snapshot.setSlowTaskCount(0L);
        snapshot.setHighRetryTaskCount(0L);
        snapshot.setActiveAlertCount(activeAlerts);
        snapshot.setSnoozedAlertCount(0L);
        snapshot.setAcknowledgedAlertCount(0L);
        snapshot.setTotalTokens(totalTokens);
        snapshot.setAvgDurationMs(0L);
        snapshot.setMaxDurationMs(0L);
        snapshot.setMetrics(Map.of());
        return snapshot;
    }

    private DashboardAlertNotification notification(String id, String alertId, String level, String status) {
        DashboardAlertNotification notification = new DashboardAlertNotification();
        notification.setId(id);
        notification.setAlertId(alertId);
        notification.setConditionKey(alertId + ":condition");
        notification.setSeverity("critical");
        notification.setEscalationLevel(level);
        notification.setStatus(status);
        notification.setNotificationCount(1L);
        notification.setChannels(Map.of("dashboard", Map.of("enabled", true)));
        notification.setPayload(Map.of("title", "Failed tasks"));
        notification.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        notification.setLastSeenAt(LocalDateTime.now());
        return notification;
    }

    private void mockNotificationPersistence() {
        when(dashboardAlertNotificationRepository.findByAlertIdAndConditionKeyAndEscalationLevel(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(dashboardAlertNotificationRepository.save(any(DashboardAlertNotification.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void mockDashboardBasics(Project project, Task failedTask) {
        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(projectRepository.count()).thenReturn(1L);
        when(projectRepository.findByStatusIn(any())).thenReturn(List.of(project));
        when(projectRepository.findByStatus(Project.ProjectStatus.ARCHIVED)).thenReturn(List.of());
        when(sampleRepository.count()).thenReturn(0L);
        when(sampleRepository.countByStatus(any())).thenReturn(0L);
        when(chapterArtifactRepository.count()).thenReturn(0L);
        when(chapterArtifactRepository.countByStage("draft")).thenReturn(0L);
        when(chapterArtifactRepository.countByStage("final")).thenReturn(0L);
        when(taskRepository.count()).thenReturn(1L);
        when(taskRepository.countByStatus(TaskStatus.PENDING)).thenReturn(0L);
        when(taskRepository.countByStatus(TaskStatus.RUNNING)).thenReturn(0L);
        when(taskRepository.countByStatus(TaskStatus.PARTIAL)).thenReturn(0L);
        when(taskRepository.countByStatus(TaskStatus.SUCCESS)).thenReturn(0L);
        when(taskRepository.countByStatus(TaskStatus.FAILED)).thenReturn(1L);
        when(taskRepository.countByStatus(TaskStatus.CANCELLED)).thenReturn(0L);
        when(taskRepository.findByStatus(TaskStatus.PARTIAL)).thenReturn(List.of());
        when(taskRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of(failedTask));
        when(dashboardMetricSnapshotRepository.save(any(DashboardMetricSnapshot.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.countByProjectId(project.getId())).thenReturn(1L);
        when(taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.FAILED)).thenReturn(1L);
        when(taskRepository.countByProjectIdAndStatus(project.getId(), TaskStatus.PARTIAL)).thenReturn(0L);
        when(taskRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(
            eq(project.getId()),
            eq(TaskStatus.PARTIAL),
            any(Pageable.class)
        )).thenReturn(List.of());
        when(taskRepository.findByProjectIdOrderByCreatedAtDesc(eq(project.getId()), any(Pageable.class)))
            .thenReturn(List.of(failedTask));
        when(sampleRepository.countByProjectId(project.getId())).thenReturn(0L);
        when(sampleRepository.countByProjectIdAndStatus(project.getId(), com.novel.system.entity.Sample.SampleStatus.ANALYZED))
            .thenReturn(0L);
        when(skillProfileRepository.countByProjectId(project.getId())).thenReturn(0L);
        when(outlineArtifactRepository.countByProjectId(project.getId())).thenReturn(0L);
        when(chapterArtifactRepository.countByProjectId(project.getId())).thenReturn(0L);
        when(chapterArtifactRepository.countByProjectIdAndStage(project.getId(), "draft")).thenReturn(0L);
        when(chapterArtifactRepository.countByProjectIdAndStage(project.getId(), "final")).thenReturn(0L);
        when(memoryArtifactRepository.countByProjectId(project.getId())).thenReturn(0L);
        when(graphArtifactRepository.countByProjectId(project.getId())).thenReturn(0L);
        when(retrievalArtifactRepository.countByProjectId(project.getId())).thenReturn(0L);
        when(pythonClientService.checkHealth()).thenReturn(true);
        when(taskExecutorService.getTaskProgress(any(Task.class))).thenReturn(Map.of("percent", 100, "label", "done"));
    }
}
