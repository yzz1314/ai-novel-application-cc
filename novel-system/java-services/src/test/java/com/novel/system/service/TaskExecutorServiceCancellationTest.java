package com.novel.system.service;

import com.novel.system.entity.Task;
import com.novel.system.entity.Task.TaskStatus;
import com.novel.system.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskExecutorServiceCancellationTest {

    @TempDir
    Path tempDir;

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private PythonClientService pythonClientService;
    @Mock
    private SampleService sampleService;
    @Mock
    private AnalysisResultService analysisResultService;
    @Mock
    private ModelProfileService modelProfileService;
    @Mock
    private SkillService skillService;
    @Mock
    private OutlineArtifactService outlineArtifactService;
    @Mock
    private ChapterArtifactService chapterArtifactService;
    @Mock
    private MemoryArtifactService memoryArtifactService;
    @Mock
    private GraphArtifactDbService graphArtifactDbService;
    @Mock
    private RetrievalArtifactDbService retrievalArtifactDbService;

    private TaskExecutorService taskExecutorService;

    @BeforeEach
    void setUp() {
        taskExecutorService = new TaskExecutorService(
            taskRepository,
            pythonClientService,
            sampleService,
            analysisResultService,
            modelProfileService,
            skillService,
            outlineArtifactService,
            chapterArtifactService,
            memoryArtifactService,
            graphArtifactDbService,
            retrievalArtifactDbService
        );
        ReflectionTestUtils.setField(taskExecutorService, "basePath", tempDir.toString());
    }

    @Test
    void executeTaskAsyncSkipsTaskCancelledBeforeStart() throws Exception {
        Task task = task("task_cancelled_before_start", TaskStatus.CANCELLED);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

        Task result = taskExecutorService.executeTaskAsync(task.getId()).join();

        assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        verify(pythonClientService, never()).callAgent(any(), anyMap());
        verify(taskRepository, never()).save(any(Task.class));
        assertThat(taskEventLog(task))
            .contains("start_skipped_cancelled")
            .contains("cancelled_before_start");
    }

    @Test
    void cancelTaskPreservesLocalCancellationWhenPythonNotificationFails() throws Exception {
        Task task = task("task_running_cancel", TaskStatus.RUNNING);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("python unavailable"))
            .when(pythonClientService)
            .cancelTask(task.getId());

        Task result = taskExecutorService.cancelTask(task.getId());

        assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(result.getFinishedAt()).isNotNull();
        verify(pythonClientService).cancelTask(task.getId());
        assertThat(taskEventLog(task))
            .contains("\"pythonCancelNotified\":false")
            .contains("python unavailable");
    }

    @Test
    void coverageCheckCompletionSyncsCoverageDiagnosticsToDb() {
        Task task = task("task_coverage_sync", TaskStatus.PENDING);
        task.setTaskType("coverage_check");
        task.setAgentName("coverage_check");
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pythonClientService.buildAgentRequest(
            task.getId(),
            task.getProjectId(),
            task.getTaskType(),
            task.getInputRefs(),
            task.getParameters()
        )).thenReturn(Map.of());
        when(pythonClientService.callAgent("coverage_check", Map.of())).thenReturn(Map.of(
            "status", "partial",
            "structured_output", Map.of(
                "sample_id", "sample_1",
                "repair_queue_count", 1
            )
        ));

        Task result = taskExecutorService.executeTaskAsync(task.getId()).join();

        assertThat(result.getStatus()).isEqualTo(TaskStatus.PARTIAL);
        verify(sampleService).syncSampleStructureFromWorkspace(task.getProjectId(), "sample_1");
        verify(analysisResultService).syncAnalysisResultsFromWorkspace(task.getProjectId(), "sample_1");
        verify(sampleService, never()).updateSampleStatus(any(), any());
    }

    @Test
    void createTaskAppliesConfigurableRetryPolicy() throws Exception {
        when(modelProfileService.getDefaultProfileId()).thenReturn(null);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId("task_retry_policy");
            }
            return saved;
        });

        Task task = taskExecutorService.createTask(
            "project_retry_policy",
            "full_text_analysis",
            "full_text_analysis",
            Map.of("sample_id", "sample_1"),
            Map.of(
                "sample_id", "sample_1",
                "retry_policy", Map.of(
                    "max_retries", 5,
                    "initial_delay_ms", 1000,
                    "max_delay_ms", 8000,
                    "multiplier", 3
                )
            )
        );

        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getMaxRetries()).isEqualTo(5);
        assertThat(taskEventLog(task))
            .contains("\"eventType\":\"created\"")
            .contains("\"maxRetries\":5")
            .contains("\"initialDelayMs\":1000")
            .contains("\"multiplier\":3.0");
    }

    @Test
    void executeTaskAsyncRecordsBackoffPolicyForRetryableFailure() throws Exception {
        Task task = task("task_retry_backoff", TaskStatus.PENDING);
        task.setMaxRetries(4);
        task.setParameters(Map.of(
            "sample_id", "sample_1",
            "retry_policy", Map.of(
                "initial_delay_ms", 500,
                "max_delay_ms", 5000,
                "multiplier", 3
            )
        ));
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pythonClientService.buildAgentRequest(
            task.getId(),
            task.getProjectId(),
            task.getTaskType(),
            task.getInputRefs(),
            task.getParameters()
        )).thenReturn(Map.of());
        when(pythonClientService.callAgent(task.getAgentName(), Map.of()))
            .thenThrow(new RuntimeException("timeout talking to python"))
            .thenReturn(Map.of(
                "status", "success",
                "structured_output", Map.of("sample_id", "sample_1")
            ));

        Task result = taskExecutorService.executeTaskAsync(task.getId()).join();

        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(result.getRetryCount()).isEqualTo(1);
        assertThat(result.getMetrics()).containsKey("retry_policy");
        Map<?, ?> retryPolicy = (Map<?, ?>) result.getMetrics().get("retry_policy");
        assertThat(retryPolicy.get("retryCount")).isEqualTo(1);
        assertThat(retryPolicy.get("maxRetries")).isEqualTo(4);
        assertThat(retryPolicy.get("delayMs")).isEqualTo(500L);
        assertThat(taskEventLog(task))
            .contains("\"eventType\":\"auto_retry_scheduled\"")
            .contains("\"delayMs\":500")
            .contains("\"remainingRetries\":3");
    }

    private Task task(String taskId, TaskStatus status) {
        Task task = new Task();
        task.setId(taskId);
        task.setProjectId("project_cancel_test");
        task.setTaskType("full_text_analysis");
        task.setAgentName("full_text_analysis");
        task.setStatus(status);
        task.setInputRefs(Map.of("sample_id", "sample_1"));
        task.setParameters(Map.of("sample_id", "sample_1"));
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }

    private String taskEventLog(Task task) throws Exception {
        Path path = tempDir
            .resolve("projects")
            .resolve(task.getProjectId())
            .resolve("logs")
            .resolve("tasks")
            .resolve(task.getId() + ".jsonl");
        return Files.readString(path);
    }
}
