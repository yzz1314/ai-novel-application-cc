package com.novel.system.service;

import com.novel.system.entity.Project;
import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.repository.ChapterArtifactRepository;
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
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
            projectRepository,
            sampleRepository,
            taskRepository,
            chapterArtifactRepository,
            skillProfileRepository,
            outlineArtifactRepository,
            memoryArtifactRepository,
            graphArtifactRepository,
            retrievalArtifactRepository,
            pythonClientService,
            taskExecutorService
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

        List<Map<String, Object>> nextActions = (List<Map<String, Object>>) dashboard.get("nextActions");
        assertThat(nextActions)
            .extracting(action -> action.get("target"))
            .contains("tasks");
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
}
