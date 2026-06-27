package com.novel.system.service;

import com.novel.system.entity.Project;
import com.novel.system.entity.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.any;

@ExtendWith(MockitoExtension.class)
class RetrievalArtifactServiceTest {

    private static final String PROJECT_ID = "project_retrieval_invalidate";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    @Mock
    private TaskExecutorService taskExecutorService;

    private RetrievalArtifactService retrievalArtifactService;

    @BeforeEach
    void setUp() {
        retrievalArtifactService = new RetrievalArtifactService(projectService, taskExecutorService);
        ReflectionTestUtils.setField(retrievalArtifactService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Retrieval Invalidate Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
    }

    @Test
    void invalidateCachesSnapshotsAndRemovesStaleRetrievalArtifacts() throws Exception {
        writeProjectFile("indexes/retrieval_config.json", "{\"top_k\":8}");
        writeProjectFile("indexes/bm25/index_summary.json", "{\"document_count\":3}");
        writeProjectFile("indexes/vector/index_summary.json", "{\"document_count\":3}");
        writeProjectFile("indexes/hybrid/index_summary.json", "{\"document_count\":3}");
        writeProjectFile("indexes/retrieval_index_report.json", "{\"quality_evaluation\":{\"score\":81}}");
        writeProjectFile("indexes/retrieval_quality_report.json", "{\"score\":78}");
        writeProjectFile(
            "indexes/bm25/context_packs/book_a_v1_c1.json",
            "{\"book_id\":\"book_a\",\"volume_number\":1,\"chapter_number\":1}"
        );

        Map<String, Object> response = retrievalArtifactService.invalidateCaches(PROJECT_ID, Map.of(
            "actor", "tester",
            "reason", "config changed"
        ));

        assertThat(response)
            .containsEntry("status", "invalidated")
            .containsEntry("deletedCount", 4)
            .containsEntry("remainingContextPackCount", 0);
        assertThat(Files.exists(projectRoot().resolve("indexes/bm25/context_packs/book_a_v1_c1.json"))).isFalse();
        assertThat(Files.exists(projectRoot().resolve("indexes/hybrid/index_summary.json"))).isFalse();
        assertThat(Files.exists(projectRoot().resolve("indexes/retrieval_index_report.json"))).isFalse();
        assertThat(Files.exists(projectRoot().resolve("indexes/retrieval_quality_report.json"))).isFalse();
        assertThat(Files.exists(projectRoot().resolve("indexes/bm25/index_summary.json"))).isTrue();
        assertThat(Files.exists(projectRoot().resolve("indexes/vector/index_summary.json"))).isTrue();

        String versionPath = String.valueOf(response.get("versionPath"));
        String snapshot = Files.readString(projectRoot().resolve(versionPath), StandardCharsets.UTF_8);
        assertThat(versionPath).startsWith("indexes/versions/retrieval_invalidation_");
        assertThat(snapshot)
            .contains("\"actor\" : \"tester\"")
            .contains("\"reason\" : \"config changed\"")
            .contains("book_a_v1_c1")
            .contains("\"quality_evaluation\"");
    }

    @Test
    void invalidateCachesCanClearIndexSummariesWhenRequested() throws Exception {
        writeProjectFile("indexes/bm25/index_summary.json", "{\"document_count\":3}");
        writeProjectFile("indexes/vector/index_summary.json", "{\"document_count\":3}");

        Map<String, Object> response = retrievalArtifactService.invalidateCaches(PROJECT_ID, Map.of(
            "clearIndexSummaries", true
        ));

        assertThat(response.get("deletedCount")).isEqualTo(2);
        assertThat(Files.exists(projectRoot().resolve("indexes/bm25/index_summary.json"))).isFalse();
        assertThat(Files.exists(projectRoot().resolve("indexes/vector/index_summary.json"))).isFalse();
    }

    @Test
    void benchmarkReportCanBeReadAndEvaluateCreatesRetrievalIndexTask() throws Exception {
        writeProjectFile("indexes/retrieval_benchmark_report.json", """
            {
              "status": "passed",
              "case_count": 2,
              "passed_count": 2,
              "hit_rate": 1.0,
              "mean_reciprocal_rank": 0.75
            }
            """);

        Map<String, Object> report = retrievalArtifactService.getBenchmarkReport(PROJECT_ID);
        assertThat(report)
            .containsEntry("exists", true)
            .containsEntry("status", "passed")
            .containsEntry("case_count", 2)
            .containsEntry("path", "indexes/retrieval_benchmark_report.json");

        AtomicReference<Map<String, Object>> taskParameters = new AtomicReference<>();
        Task task = new Task();
        task.setId("task_retrieval_benchmark");
        task.setProjectId(PROJECT_ID);
        task.setTaskType("retrieval_index");
        task.setAgentName("retrieval_index");
        when(taskExecutorService.createTask(
            eq(PROJECT_ID),
            eq("retrieval_index"),
            eq("retrieval_index"),
            eq(Map.of()),
            any()
        )).thenAnswer(invocation -> {
            taskParameters.set(invocation.getArgument(4));
            return task;
        });
        when(taskExecutorService.executeTaskAsync("task_retrieval_benchmark"))
            .thenReturn(CompletableFuture.completedFuture(task));

        Task benchmarkTask = retrievalArtifactService.evaluateBenchmark(PROJECT_ID, Map.of(
            "top_k", 5
        ));

        assertThat(benchmarkTask.getId()).isEqualTo("task_retrieval_benchmark");
        assertThat(taskParameters.get())
            .containsEntry("project_id", PROJECT_ID)
            .containsEntry("run_benchmark", true)
            .containsEntry("top_k", 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createIndexVersionListsDetailsAndRebuildSnapshotsPreviousIndexes() throws Exception {
        writeProjectFile("indexes/retrieval_config.json", "{\"top_k\":8}");
        writeProjectFile("indexes/bm25/index_summary.json", "{\"document_count\":3,\"engine\":\"bm25\"}");
        writeProjectFile("indexes/vector/index_summary.json", "{\"document_count\":3,\"engine\":\"hash_vector\"}");
        writeProjectFile("indexes/hybrid/index_summary.json", """
            {
              "document_count": 3,
              "quality_evaluation": {
                "status": "good",
                "score": 82
              }
            }
            """);
        writeProjectFile("indexes/retrieval_index_report.json", "{\"quality_evaluation\":{\"score\":81}}");
        writeProjectFile("indexes/retrieval_quality_report.json", "{\"status\":\"good\",\"score\":80}");
        writeProjectFile(
            "indexes/bm25/context_packs/book_a_v1_c1.json",
            "{\"book_id\":\"book_a\",\"volume_number\":1,\"chapter_number\":1}"
        );

        Map<String, Object> version = retrievalArtifactService.createIndexVersion(PROJECT_ID, Map.of(
            "actor", "tester",
            "reason", "manual_review",
            "note", "before config tuning"
        ));
        String versionId = String.valueOf(version.get("id"));
        String versionPath = String.valueOf(version.get("path"));

        assertThat(version)
            .containsEntry("reason", "manual_review")
            .containsEntry("actor", "tester")
            .containsEntry("contextPackCount", 1)
            .containsEntry("qualityScore", 80)
            .containsEntry("qualityStatus", "good");
        assertThat(versionId).startsWith("retrieval_index_");
        assertThat(versionPath).startsWith("indexes/versions/retrieval_index_");
        assertThat(Files.exists(projectRoot().resolve(versionPath))).isTrue();
        assertThat((Map<String, Object>) version.get("documentCounts"))
            .containsEntry("bm25", 3)
            .containsEntry("vector", 3)
            .containsEntry("hybrid", 3);

        Map<String, Object> detail = retrievalArtifactService.getIndexVersion(PROJECT_ID, versionId);
        assertThat(detail)
            .containsEntry("id", versionId)
            .containsEntry("reason", "manual_review")
            .containsEntry("path", versionPath);
        assertThat((Map<String, Object>) detail.get("indexes")).containsKeys("bm25", "vector", "hybrid", "rebuildReport");
        assertThat((List<Map<String, Object>>) detail.get("contextPacks")).hasSize(1);

        assertThat(retrievalArtifactService.listIndexVersions(PROJECT_ID))
            .hasSize(1)
            .first()
            .satisfies(item -> assertThat(item)
                .containsEntry("id", versionId)
                .containsEntry("bm25DocumentCount", 3)
                .containsEntry("vectorDocumentCount", 3)
                .containsEntry("hybridDocumentCount", 3)
                .containsEntry("path", versionPath));

        AtomicReference<Map<String, Object>> taskParameters = new AtomicReference<>();
        Task task = new Task();
        task.setId("task_retrieval_rebuild");
        task.setProjectId(PROJECT_ID);
        task.setTaskType("retrieval_index");
        task.setAgentName("retrieval_index");
        when(taskExecutorService.createTask(
            eq(PROJECT_ID),
            eq("retrieval_index"),
            eq("retrieval_index"),
            eq(Map.of()),
            any()
        )).thenAnswer(invocation -> {
            taskParameters.set(invocation.getArgument(4));
            return task;
        });
        when(taskExecutorService.executeTaskAsync("task_retrieval_rebuild"))
            .thenReturn(CompletableFuture.completedFuture(task));

        Task rebuildTask = retrievalArtifactService.rebuildIndexes(PROJECT_ID, Map.of(
            "actor", "tester",
            "top_k", 12
        ));

        assertThat(rebuildTask.getId()).isEqualTo("task_retrieval_rebuild");
        assertThat(taskParameters.get())
            .containsEntry("project_id", PROJECT_ID)
            .containsEntry("top_k", 12)
            .containsKey("previous_retrieval_version_id")
            .containsKey("previous_retrieval_version_path");
        assertThat(String.valueOf(taskParameters.get().get("previous_retrieval_version_path")))
            .startsWith("indexes/versions/retrieval_index_");
        assertThat(retrievalArtifactService.listIndexVersions(PROJECT_ID)).hasSize(2);
    }

    private Path projectRoot() {
        return tempDir.resolve("projects").resolve(PROJECT_ID);
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
