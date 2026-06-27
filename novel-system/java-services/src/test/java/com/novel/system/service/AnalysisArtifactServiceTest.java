package com.novel.system.service;

import com.novel.system.entity.Project;
import com.novel.system.entity.Sample;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisArtifactServiceTest {

    private static final String PROJECT_ID = "project_analysis_issues";
    private static final String SAMPLE_ID = "sample_analysis_issues";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;
    @Mock
    private SampleService sampleService;
    @Mock
    private TaskExecutorService taskExecutorService;

    private AnalysisArtifactService analysisArtifactService;

    @BeforeEach
    void setUp() {
        analysisArtifactService = new AnalysisArtifactService(projectService, sampleService, taskExecutorService);
        ReflectionTestUtils.setField(analysisArtifactService, "basePath", tempDir.toString());

        when(projectService.getProject(PROJECT_ID)).thenReturn(project());
        when(sampleService.getSample(SAMPLE_ID)).thenReturn(sample());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSampleAnalysisIssuesMergesCoverageQueueAndChunkMetadata() throws Exception {
        writeWorkspaceFixture();

        Map<String, Object> response = analysisArtifactService.getSampleAnalysisIssues(PROJECT_ID, SAMPLE_ID);
        List<Map<String, Object>> issues = (List<Map<String, Object>>) response.get("issues");

        assertThat(response)
            .containsEntry("projectId", PROJECT_ID)
            .containsEntry("sampleId", SAMPLE_ID)
            .containsEntry("totalCount", 2)
            .containsEntry("missingCount", 1L)
            .containsEntry("failedCount", 1L);
        assertThat((List<String>) response.get("issueChunkIds")).containsExactly("chunk_2", "chunk_3");

        assertThat(issues).extracting(issue -> issue.get("chunkId")).containsExactly("chunk_2", "chunk_3");
        assertThat(issues.get(0))
            .containsEntry("type", "failed")
            .containsEntry("priority", "critical")
            .containsEntry("error", "LLM timeout")
            .containsEntry("chunkPath", "samples/chunks/sample_analysis_issues/chunk_2.json")
            .containsEntry("analysisPath", "analysis/per_chunk/sample_analysis_issues/chunk_2_analysis.json")
            .containsEntry("chunkIndex", 2)
            .containsEntry("chapterIndex", 1);
        assertThat((String) issues.get(0).get("preview")).contains("failed chunk text");

        assertThat(issues.get(1))
            .containsEntry("type", "missing")
            .containsEntry("priority", "high")
            .containsEntry("error", "未生成逐块分析结果")
            .containsEntry("chunkPath", "samples/chunks/sample_analysis_issues/chunk_3.json")
            .containsEntry("analysisPath", null)
            .containsEntry("chunkIndex", 3);
    }

    private void writeWorkspaceFixture() throws Exception {
        writeProjectFile("samples/chunks/" + SAMPLE_ID + "/chunk_1.json", """
            {
              "id": "chunk_1",
              "chunk_index": 1,
              "chapter_index": 1,
              "text": "successful chunk text"
            }
            """);
        writeProjectFile("samples/chunks/" + SAMPLE_ID + "/chunk_2.json", """
            {
              "id": "chunk_2",
              "chunk_index": 2,
              "chapter_index": 1,
              "text": "failed chunk text"
            }
            """);
        writeProjectFile("samples/chunks/" + SAMPLE_ID + "/chunk_3.json", """
            {
              "id": "chunk_3",
              "chunk_index": 3,
              "chapter_index": 2,
              "text": "missing chunk text"
            }
            """);
        writeProjectFile("analysis/per_chunk/" + SAMPLE_ID + "/chunk_2_analysis.json", """
            {
              "chunk_id": "chunk_2",
              "chunk_index": 2,
              "error": "LLM timeout"
            }
            """);
        writeProjectFile("analysis/coverage/" + SAMPLE_ID + "_coverage.json", """
            {
              "status": "failed",
              "analysis_coverage": {
                "missing_analysis_chunks": ["chunk_3"],
                "failed_chunks": [
                  {
                    "chunk_id": "chunk_2",
                    "error": "LLM timeout",
                    "path": "analysis/per_chunk/sample_analysis_issues/chunk_2_analysis.json"
                  }
                ]
              },
              "repair_queue": {
                "status": "ready",
                "total_count": 2,
                "chunk_ids": ["chunk_2", "chunk_3"],
                "items": [
                  {
                    "chunk_id": "chunk_2",
                    "reason": "failed_analysis",
                    "priority": "critical",
                    "error": "LLM timeout",
                    "path": "analysis/per_chunk/sample_analysis_issues/chunk_2_analysis.json"
                  },
                  {
                    "chunk_id": "chunk_3",
                    "reason": "missing_analysis",
                    "priority": "high"
                  }
                ]
              },
              "repair_queue_path": "analysis/repairs/sample_analysis_issues_repair_queue.json"
            }
            """);
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve("projects").resolve(PROJECT_ID).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Project project() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Analysis Issues");
        return project;
    }

    private Sample sample() {
        Sample sample = new Sample();
        sample.setId(SAMPLE_ID);
        sample.setProjectId(PROJECT_ID);
        sample.setFileName("sample.md");
        sample.setFilePath("sample.md");
        sample.setFileHash("hash");
        sample.setFileSizeBytes(100L);
        sample.setStatus(Sample.SampleStatus.CHUNKED);
        return sample;
    }
}
