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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    private static final String PROJECT_ID = "project_artifact_restore";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    private ArtifactService artifactService;

    @BeforeEach
    void setUp() {
        artifactService = new ArtifactService(projectService);
        ReflectionTestUtils.setField(artifactService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Artifact Restore Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
    }

    @Test
    void restoreArtifactMovesArchivedFileBackToOriginalPathAndAudits() throws Exception {
        Path original = projectRoot().resolve("analysis/report.md");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "original report", StandardCharsets.UTF_8);

        Map<String, Object> archived = artifactService.archiveArtifact(PROJECT_ID, Map.of(
            "path", "analysis/report.md",
            "actor", "tester",
            "reason", "archive before restore"
        ));
        String archivedPath = String.valueOf(archived.get("archivedPath"));

        Map<String, Object> restored = artifactService.restoreArtifact(PROJECT_ID, Map.of(
            "path", archivedPath,
            "actor", "tester",
            "reason", "restore smoke"
        ));

        assertThat(restored.get("status")).isEqualTo("restored");
        assertThat(restored.get("archivedPath")).isEqualTo(archivedPath);
        assertThat(restored.get("restoredPath")).isEqualTo("analysis/report.md");
        assertThat(Files.exists(projectRoot().resolve(archivedPath))).isFalse();
        assertThat(Files.readString(original)).isEqualTo("original report");

        String auditLog = Files.readString(projectRoot().resolve("artifacts/audit/artifact_events.jsonl"));
        assertThat(auditLog)
            .contains("\"action\":\"archive\"")
            .contains("\"action\":\"restore\"")
            .contains("\"restoredPath\":\"analysis/report.md\"");
    }

    @Test
    void restoreArtifactDoesNotOverwriteExistingTarget() throws Exception {
        Path original = projectRoot().resolve("novel/chapter.md");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "archived version", StandardCharsets.UTF_8);

        Map<String, Object> archived = artifactService.archiveArtifact(PROJECT_ID, Map.of(
            "path", "novel/chapter.md"
        ));
        String archivedPath = String.valueOf(archived.get("archivedPath"));
        Files.writeString(original, "new version", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> artifactService.restoreArtifact(PROJECT_ID, Map.of(
            "path", archivedPath
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Restore target already exists");

        assertThat(Files.readString(original)).isEqualTo("new version");
        assertThat(Files.readString(projectRoot().resolve(archivedPath))).isEqualTo("archived version");
    }

    private Path projectRoot() {
        return tempDir.resolve("projects").resolve(PROJECT_ID);
    }
}
