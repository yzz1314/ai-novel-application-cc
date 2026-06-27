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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

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
    void deleteArchivedArtifactPermanentlyRemovesArchivedFileAndAudits() throws Exception {
        writeProjectFile("analysis/obsolete.md", "obsolete report");
        Map<String, Object> archived = artifactService.archiveArtifact(PROJECT_ID, Map.of(
            "path", "analysis/obsolete.md",
            "actor", "tester",
            "reason", "archive before delete"
        ));
        String archivedPath = String.valueOf(archived.get("archivedPath"));

        Map<String, Object> deleted = artifactService.deleteArchivedArtifact(PROJECT_ID, Map.of(
            "path", archivedPath,
            "actor", "reviewer",
            "reason", "cleanup archived artifact"
        ));

        assertThat(deleted)
            .containsEntry("status", "deleted")
            .containsEntry("archivedPath", archivedPath)
            .containsEntry("originalPath", "analysis/obsolete.md");
        assertThat(Files.exists(projectRoot().resolve(archivedPath))).isFalse();
        assertThat(Files.exists(projectRoot().resolve("analysis/obsolete.md"))).isFalse();
        assertThat(auditLog())
            .contains("\"action\":\"delete_archived\"")
            .contains("\"archivedPath\":\"" + archivedPath + "\"")
            .contains("\"originalPath\":\"analysis/obsolete.md\"")
            .contains("\"actor\":\"reviewer\"");
    }

    @Test
    void deleteArchivedArtifactRejectsActiveWorkspaceFile() throws Exception {
        writeProjectFile("analysis/live.md", "live report");

        assertThatThrownBy(() -> artifactService.deleteArchivedArtifact(PROJECT_ID, Map.of(
            "path", "analysis/live.md"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Artifact is not in archive");

        assertThat(Files.readString(projectRoot().resolve("analysis/live.md"))).isEqualTo("live report");
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

    @Test
    void bulkDownloadSkipsSensitiveArtifactsByDefaultAndWritesManifestAudit() throws Exception {
        writeProjectFile("analysis/report.md", "safe report");
        writeProjectFile("samples/raw/sample.txt", "sensitive source");

        ArtifactService.DownloadedArtifact downloaded = artifactService.bulkDownloadArtifacts(PROJECT_ID, Map.of(
            "paths", List.of("analysis/report.md", "samples/raw/sample.txt"),
            "actor", "tester",
            "reason", "bulk export"
        ));

        assertThat(downloaded.filename()).startsWith("artifacts_").endsWith(".zip");
        String zipText = zipEntriesAsText(downloaded.resource().getByteArray());
        assertThat(zipText)
            .contains("ENTRY:analysis/report.md")
            .doesNotContain("ENTRY:samples/raw/sample.txt")
            .contains("ENTRY:artifact_export_manifest.json")
            .contains("\"path\":\"samples/raw/sample.txt\"")
            .contains("\"reason\":\"sensitive_sample_protected\"");
        assertThat(auditLog())
            .contains("\"action\":\"bulk_export\"")
            .contains("\"includedCount\":1")
            .contains("\"skippedCount\":1")
            .contains("\"reason\":\"bulk export\"");
    }

    @Test
    void listAuditEventsReturnsRecentEventsFirstAndHonorsLimit() throws Exception {
        writeProjectFile("analysis/one.md", "one");
        writeProjectFile("analysis/two.md", "two");
        writeProjectFile("analysis/three.md", "three");

        artifactService.getArtifact(PROJECT_ID, "analysis/one.md", false, "tester", "first");
        artifactService.getArtifact(PROJECT_ID, "analysis/two.md", false, "tester", "second");
        artifactService.getArtifact(PROJECT_ID, "analysis/three.md", false, "tester", "third");

        Map<String, Object> response = artifactService.listAuditEvents(PROJECT_ID, 2);

        assertThat(response)
            .containsEntry("projectId", PROJECT_ID)
            .containsEntry("limit", 2)
            .containsEntry("count", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("reason", "third");
        assertThat(items.get(1)).containsEntry("reason", "second");
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

    private String zipEntriesAsText(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                builder.append("ENTRY:").append(entry.getName()).append('\n');
                builder.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return builder.toString();
    }
}
