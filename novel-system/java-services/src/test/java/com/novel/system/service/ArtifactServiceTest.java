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

    @Test
    void previewDownloadAndDiffWriteAuditEvents() throws Exception {
        writeProjectFile("analysis/left.md", "one\ntwo\n");
        writeProjectFile("analysis/right.md", "one\nthree\n");

        artifactService.getArtifact(PROJECT_ID, "analysis/left.md", false, "tester", "preview check");
        artifactService.downloadArtifact(PROJECT_ID, "analysis/left.md", false, "tester", "download check");
        artifactService.diffArtifacts(PROJECT_ID, Map.of(
            "leftPath", "analysis/left.md",
            "rightPath", "analysis/right.md",
            "actor", "tester",
            "reason", "diff check"
        ));

        String auditLog = auditLog();
        assertThat(auditLog)
            .contains("\"action\":\"preview\"")
            .contains("\"action\":\"download\"")
            .contains("\"action\":\"diff\"")
            .contains("\"path\":\"analysis/left.md\"")
            .contains("\"leftPath\":\"analysis/left.md\"")
            .contains("\"rightPath\":\"analysis/right.md\"")
            .contains("\"changedLines\":2");
    }

    @Test
    void deniedSensitiveDownloadWritesAuditEvent() throws Exception {
        writeProjectFile("samples/raw/sample.txt", "sensitive source");

        assertThatThrownBy(() -> artifactService.downloadArtifact(
            PROJECT_ID,
            "samples/raw/sample.txt",
            false,
            "tester",
            "denied check"
        ))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditLog())
            .contains("\"action\":\"download_denied\"")
            .contains("\"path\":\"samples/raw/sample.txt\"")
            .contains("\"policy\":\"sample_source_protected\"")
            .contains("\"sensitive\":true");
    }

    private Path projectRoot() {
        return tempDir.resolve("projects").resolve(PROJECT_ID);
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String auditLog() throws Exception {
        return Files.readString(projectRoot().resolve("artifacts/audit/artifact_events.jsonl"));
    }
}
