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
