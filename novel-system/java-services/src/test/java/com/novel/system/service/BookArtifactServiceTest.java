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
class BookArtifactServiceTest {

    private static final String PROJECT_ID = "project_soul_restore";
    private static final String BOOK_ID = "book_1";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    @Mock
    private TaskExecutorService taskExecutorService;

    @Mock
    private ChapterArtifactService chapterArtifactService;

    @Mock
    private OutlineArtifactService outlineArtifactService;

    private BookArtifactService bookArtifactService;

    @BeforeEach
    void setUp() {
        bookArtifactService = new BookArtifactService(
            projectService,
            taskExecutorService,
            chapterArtifactService,
            outlineArtifactService
        );
        ReflectionTestUtils.setField(bookArtifactService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Project Soul Restore Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
    }

    @Test
    void readsProjectSoulVersionContent() throws Exception {
        Path version = writeProjectFile(
            "novel/soul/versions/project_soul_20260626010101000.md",
            "archived soul"
        );

        Map<String, Object> detail = bookArtifactService.getProjectSoulVersion(
            PROJECT_ID,
            BOOK_ID,
            "project_soul_20260626010101000"
        );

        assertThat(detail)
            .containsEntry("id", "project_soul_20260626010101000")
            .containsEntry("path", "novel/soul/versions/project_soul_20260626010101000.md")
            .containsEntry("content", "archived soul");
        assertThat(detail.get("sizeBytes")).isEqualTo(Files.size(version));
    }

    @Test
    void restoreProjectSoulVersionSnapshotsCurrentSoulAndUpdatesGovernance() throws Exception {
        writeProjectFile("novel/soul/project_soul.md", "current soul");
        writeProjectFile("novel/soul/versions/project_soul_20260626010101000.md", "archived soul");

        Map<String, Object> response = bookArtifactService.restoreProjectSoulVersion(
            PROJECT_ID,
            BOOK_ID,
            "project_soul_20260626010101000",
            Map.of("actor", "tester", "note", "restore check")
        );

        assertThat(Files.readString(projectRoot().resolve("novel/soul/project_soul.md"))).isEqualTo("archived soul");
        assertThat(response)
            .containsEntry("status", "restored")
            .containsEntry("restoredFromVersionId", "project_soul_20260626010101000")
            .containsEntry("content", "archived soul");

        String previousSnapshotPath = String.valueOf(response.get("previousSnapshotPath"));
        assertThat(previousSnapshotPath).startsWith("novel/soul/versions/project_soul_");
        assertThat(Files.readString(projectRoot().resolve(previousSnapshotPath))).isEqualTo("current soul");

        String governance = Files.readString(projectRoot().resolve("novel/soul/project_soul_meta.json"));
        assertThat(governance)
            .contains("\"restored_from_version_id\"")
            .contains("project_soul_20260626010101000")
            .contains("\"restored_by\"")
            .contains("tester")
            .contains("\"approval_status\" : \"pending_review\"");
    }

    @Test
    void lockedProjectSoulRejectsRestoreWithoutOverride() throws Exception {
        writeProjectFile("novel/soul/project_soul.md", "locked current soul");
        writeProjectFile("novel/soul/versions/project_soul_20260626010101000.md", "archived soul");
        writeProjectFile("novel/soul/project_soul_meta.json", """
            {
              "book_id": "book_1",
              "locked": true,
              "approval_status": "approved"
            }
            """);

        assertThatThrownBy(() -> bookArtifactService.restoreProjectSoulVersion(
            PROJECT_ID,
            BOOK_ID,
            "project_soul_20260626010101000",
            Map.of("actor", "tester")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("locked");

        assertThat(Files.readString(projectRoot().resolve("novel/soul/project_soul.md"))).isEqualTo("locked current soul");
    }

    private Path projectRoot() {
        return tempDir.resolve("projects").resolve(PROJECT_ID);
    }

    private Path writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
