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
class MemoryServiceTest {

    private static final String PROJECT_ID = "project_memory_versions";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    @Mock
    private TaskExecutorService taskExecutorService;

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(projectService, taskExecutorService);
        ReflectionTestUtils.setField(memoryService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Memory Version Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
    }

    @Test
    void createVersionSnapshotsMemoryMarkdownAndJson() throws Exception {
        writeProjectFile("memory/characters.md", "# Characters\n\n- Lin Yuan");
        writeProjectFile("memory/characters.json", "[{\"name\":\"Lin Yuan\"}]");

        Map<String, Object> version = memoryService.createVersion(PROJECT_ID, Map.of(
            "reason", "manual_check",
            "actor", "tester",
            "note", "before audit"
        ));

        assertThat(version)
            .containsEntry("reason", "manual_check")
            .containsEntry("actor", "tester")
            .containsEntry("fileCount", 2);
        String versionPath = String.valueOf(version.get("path"));
        String snapshot = Files.readString(projectRoot().resolve(versionPath), StandardCharsets.UTF_8);
        assertThat(snapshot)
            .contains("\"version_type\" : \"memory_snapshot\"")
            .contains("\"name\" : \"characters.md\"")
            .contains("Lin Yuan")
            .contains("\"note\" : \"before audit\"");
    }

    @Test
    void restoreVersionRestoresFilesAndSnapshotsCurrentMemory() throws Exception {
        writeProjectFile("memory/characters.md", "archived characters");
        writeProjectFile("memory/characters.json", "[{\"name\":\"Archived\"}]");
        Map<String, Object> archived = memoryService.createVersion(PROJECT_ID, Map.of(
            "reason", "before_bad_ingest",
            "actor", "tester"
        ));

        writeProjectFile("memory/characters.md", "current broken characters");
        writeProjectFile("memory/characters.json", "[{\"name\":\"Broken\"}]");

        Map<String, Object> response = memoryService.restoreVersion(
            PROJECT_ID,
            String.valueOf(archived.get("id")),
            Map.of("actor", "reviewer", "note", "rollback")
        );

        assertThat(response)
            .containsEntry("status", "restored")
            .containsEntry("restoredFromVersionId", archived.get("id"));
        assertThat(Files.readString(projectRoot().resolve("memory/characters.md"))).isEqualTo("archived characters");
        assertThat(Files.readString(projectRoot().resolve("memory/characters.json"))).contains("Archived");

        String previousVersionPath = String.valueOf(response.get("previousVersionPath"));
        String previousSnapshot = Files.readString(projectRoot().resolve(previousVersionPath), StandardCharsets.UTF_8);
        assertThat(previousSnapshot)
            .contains("current broken characters")
            .contains("\"reason\" : \"before_memory_restore\"");

        String restoreEventPath = String.valueOf(response.get("restoreEventPath"));
        assertThat(Files.readString(projectRoot().resolve(restoreEventPath), StandardCharsets.UTF_8))
            .contains("\"event_type\" : \"memory_restore\"")
            .contains("\"actor\" : \"reviewer\"");
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
