package com.novel.system.service;

import com.novel.system.entity.Project;
import com.novel.system.entity.RetrievalArtifact;
import com.novel.system.repository.RetrievalArtifactRepository;
import com.novel.system.repository.TaskRepository;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalArtifactDbServiceTest {

    private static final String PROJECT_ID = "project_retrieval_db_quality";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    @Mock
    private RetrievalArtifactRepository retrievalArtifactRepository;

    @Mock
    private TaskRepository taskRepository;

    private RetrievalArtifactDbService retrievalArtifactDbService;
    private final AtomicReference<RetrievalArtifact> savedArtifact = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        retrievalArtifactDbService = new RetrievalArtifactDbService(
            projectService,
            retrievalArtifactRepository,
            taskRepository
        );
        ReflectionTestUtils.setField(retrievalArtifactDbService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Retrieval DB Quality Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
        when(taskRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(retrievalArtifactRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(retrievalArtifactRepository.save(any(RetrievalArtifact.class))).thenAnswer(invocation -> {
            RetrievalArtifact artifact = invocation.getArgument(0);
            savedArtifact.set(artifact);
            return artifact;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncRetrievalPersistsQualityReportAndBudgetMetadata() throws Exception {
        writeProjectFile("indexes/retrieval_config.json", """
            {
              "use_vector": true,
              "top_k": 8
            }
            """);
        writeProjectFile("indexes/bm25/index_summary.json", """
            {
              "document_count": 4,
              "source_counts": {"sample": 2, "memory": 2}
            }
            """);
        writeProjectFile("indexes/vector/index_summary.json", """
            {
              "document_count": 4,
              "engine": "embedding_vector",
              "vector_mode": "local_embedding_fallback",
              "embedding_metadata": {
                "status": "mock",
                "model_role": "embeddingModel",
                "model": "mock-embedding",
                "local_fallback": true,
                "dimensions": 96
              }
            }
            """);
        writeProjectFile("indexes/hybrid/index_summary.json", """
            {
              "document_count": 4,
              "vector_index": {
                "engine": "embedding_vector",
                "vector_mode": "local_embedding_fallback"
              },
              "quality_evaluation": {"score": 82, "status": "good"},
              "citation_budget": {
                "usage": {
                  "selected_result_count": 3,
                  "context_utilization": 0.42
                }
              }
            }
            """);
        writeProjectFile("indexes/retrieval_index_report.json", """
            {
              "vector_index": {
                "engine": "embedding_vector",
                "vector_mode": "local_embedding_fallback",
                "embedding_metadata": {
                  "model_role": "embeddingModel",
                  "local_fallback": true
                }
              },
              "model_gateway": {
                "embedding_index": {
                  "vector_mode": "local_embedding_fallback",
                  "model_role": "embeddingModel",
                  "local_fallback": true
                }
              },
              "quality_evaluation": {"score": 79, "status": "needs_review"},
              "citation_budget": {
                "usage": {"selected_result_count": 2}
              }
            }
            """);
        writeProjectFile("indexes/retrieval_quality_report.json", """
            {
              "projectId": "project_retrieval_db_quality",
              "status": "good",
              "score": 86,
              "checks": [
                {"key": "quality_score", "status": "pass"}
              ],
              "warnings": [],
              "recommendations": ["Keep index freshness under review."]
            }
            """);
        writeProjectFile("indexes/retrieval_benchmark_report.json", """
            {
              "project_id": "project_retrieval_db_quality",
              "status": "passed",
              "case_count": 3,
              "passed_count": 2,
              "hit_count": 3,
              "hit_rate": 1.0,
              "mean_reciprocal_rank": 0.833333,
              "average_quality_score": 78.5,
              "cases": [
                {"id": "jade_token", "passed": true},
                {"id": "scene_skill", "passed": true},
                {"id": "canon_query", "passed": false}
              ]
            }
            """);
        writeProjectFile("indexes/bm25/context_packs/book_a_v1_c2.json", """
            {
              "book_id": "book_a",
              "volume_number": 1,
              "chapter_number": 2,
              "sources": {"memory": 1},
              "quality_evaluation": {"score": 84, "status": "good"},
              "citation_budget": {
                "usage": {"selected_result_count": 2}
              }
            }
            """);

        Map<String, Object> response = retrievalArtifactDbService.syncRetrievalFromWorkspace(PROJECT_ID, Map.of());

        assertThat(response)
            .containsEntry("status", "synced")
            .containsEntry("bm25DocumentCount", 4)
            .containsEntry("vectorDocumentCount", 4)
            .containsEntry("hybridDocumentCount", 4)
            .containsEntry("contextPackCount", 1)
            .containsEntry("qualityReportPath", "indexes/retrieval_quality_report.json")
            .containsEntry("qualityScore", 86)
            .containsEntry("qualityStatus", "good")
            .containsEntry("benchmarkReportPath", "indexes/retrieval_benchmark_report.json")
            .containsEntry("benchmarkStatus", "passed")
            .containsEntry("benchmarkCaseCount", 3)
            .containsEntry("benchmarkPassedCount", 2)
            .containsEntry("benchmarkHitRate", 1.0)
            .containsEntry("benchmarkMeanReciprocalRank", 0.833333)
            .containsEntry("vectorMode", "local_embedding_fallback")
            .containsEntry("vectorEngine", "embedding_vector");

        Map<String, Object> qualityReport = (Map<String, Object>) response.get("qualityReport");
        assertThat(qualityReport)
            .containsEntry("score", 86)
            .containsEntry("status", "good");
        List<Map<String, Object>> checks = (List<Map<String, Object>>) qualityReport.get("checks");
        assertThat(checks).hasSize(1);
        assertThat(checks.get(0)).containsEntry("key", "quality_score");

        Map<String, Object> benchmarkReport = (Map<String, Object>) response.get("benchmarkReport");
        assertThat(benchmarkReport)
            .containsEntry("status", "passed")
            .containsEntry("case_count", 3)
            .containsEntry("passed_count", 2)
            .containsEntry("hit_rate", 1.0);
        List<Map<String, Object>> cases = (List<Map<String, Object>>) benchmarkReport.get("cases");
        assertThat(cases).hasSize(3);
        assertThat(cases.get(0)).containsEntry("id", "jade_token");

        Map<String, Object> retrievalMetadata = (Map<String, Object>) response.get("retrievalMetadata");
        assertThat(retrievalMetadata)
            .containsEntry("latestQualityScore", 86)
            .containsEntry("latestQualityStatus", "good")
            .containsEntry("latestBenchmarkStatus", "passed")
            .containsEntry("latestBenchmarkCaseCount", 3)
            .containsEntry("latestBenchmarkPassedCount", 2)
            .containsEntry("latestBenchmarkHitRate", 1.0)
            .containsEntry("latestBenchmarkMeanReciprocalRank", 0.833333)
            .containsEntry("latestVectorMode", "local_embedding_fallback")
            .containsEntry("latestVectorEngine", "embedding_vector");
        Map<String, Object> latestQualityReport = (Map<String, Object>) retrievalMetadata.get("latestQualityReport");
        Map<String, Object> latestBenchmarkReport = (Map<String, Object>) retrievalMetadata.get("latestBenchmarkReport");
        Map<String, Object> latestQualityEvaluation = (Map<String, Object>) retrievalMetadata.get("latestQualityEvaluation");
        Map<String, Object> latestCitationBudget = (Map<String, Object>) retrievalMetadata.get("latestCitationBudget");
        Map<String, Object> latestEmbeddingIndex = (Map<String, Object>) retrievalMetadata.get("latestEmbeddingIndex");
        assertThat(latestQualityReport).containsEntry("score", 86);
        assertThat(latestBenchmarkReport).containsEntry("status", "passed");
        assertThat(latestQualityEvaluation).containsEntry("score", 82);
        assertThat(latestCitationBudget).containsKey("usage");
        assertThat(latestEmbeddingIndex)
            .containsEntry("model_role", "embeddingModel")
            .containsEntry("local_fallback", true);

        RetrievalArtifact saved = savedArtifact.get();
        assertThat(saved.getQualityReportPath()).isEqualTo("indexes/retrieval_quality_report.json");
        assertThat(saved.getQualityReport())
            .containsEntry("score", 86)
            .containsEntry("status", "good");
        assertThat(saved.getBenchmarkReportPath()).isEqualTo("indexes/retrieval_benchmark_report.json");
        assertThat(saved.getBenchmarkReport())
            .containsEntry("status", "passed")
            .containsEntry("case_count", 3);

        when(retrievalArtifactRepository.findByProjectIdOrderByUpdatedAtDesc(PROJECT_ID)).thenReturn(List.of(saved));
        Map<String, Object> summary = retrievalArtifactDbService.listRetrieval(PROJECT_ID).get(0);
        assertThat(summary)
            .containsEntry("qualityReportPath", "indexes/retrieval_quality_report.json")
            .containsEntry("qualityScore", 86)
            .containsEntry("qualityStatus", "good")
            .containsEntry("benchmarkReportPath", "indexes/retrieval_benchmark_report.json")
            .containsEntry("benchmarkStatus", "passed")
            .containsEntry("benchmarkCaseCount", 3)
            .containsEntry("benchmarkPassedCount", 2)
            .containsEntry("vectorMode", "local_embedding_fallback")
            .containsEntry("vectorEngine", "embedding_vector");
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
