package com.novel.system.service;

import com.novel.system.entity.Project;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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

    private Path projectRoot() {
        return tempDir.resolve("projects").resolve(PROJECT_ID);
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
