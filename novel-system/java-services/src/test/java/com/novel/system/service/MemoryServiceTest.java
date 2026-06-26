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

    @Test
    void applyAuditIssueFixUpdatesMemoryAndMarksIssueResolved() throws Exception {
        writeProjectFile("memory/characters.json", """
            [
              {
                "character_id": "char_lin",
                "name": "Lin Yuan",
                "appearances": [1, 3],
                "first_mentioned": 1,
                "last_updated": 1
              }
            ]
            """);
        writeProjectFile("memory/audits/memory_audit_test.json", """
            {
              "project_id": "project_memory_versions",
              "book_id": "default",
              "issue_count": 1,
              "issues": [
                {
                  "issue_id": "issue_last_updated",
                  "issue_type": "character",
                  "severity": "minor",
                  "title": "last_updated stale",
                  "description": "last_updated is behind appearances",
                  "suggestion": "update last_updated",
                  "fix": {
                    "action": "set_field",
                    "memory_file": "characters.json",
                    "match": {
                      "character_id": "char_lin"
                    },
                    "field": "last_updated",
                    "value": 3
                  }
                }
              ]
            }
            """);

        Map<String, Object> response = memoryService.applyAuditIssueFix(
            PROJECT_ID,
            "memory_audit_test",
            0,
            Map.of("actor", "tester", "note", "apply deterministic fix")
        );

        assertThat(response)
            .containsEntry("reportId", "memory_audit_test")
            .containsKey("beforeVersionId")
            .containsKey("eventPath");
        assertThat(Files.readString(projectRoot().resolve("memory/characters.json"), StandardCharsets.UTF_8))
            .contains("\"last_updated\" : 3");

        String report = Files.readString(projectRoot().resolve("memory/audits/memory_audit_test.json"), StandardCharsets.UTF_8);
        assertThat(report)
            .contains("\"resolution_status\" : \"resolved\"")
            .contains("\"fix_applied\" : true")
            .contains("\"changedCount\" : 1");

        String beforeVersionPath = String.valueOf(response.get("beforeVersionPath"));
        assertThat(Files.readString(projectRoot().resolve(beforeVersionPath), StandardCharsets.UTF_8))
            .contains("last_updated")
            .contains("1")
            .contains("\"reason\" : \"before_memory_audit_fix\"");

        String eventPath = String.valueOf(response.get("eventPath"));
        assertThat(Files.readString(projectRoot().resolve(eventPath), StandardCharsets.UTF_8))
            .contains("\"event_type\" : \"memory_audit_fix\"")
            .contains("\"actor\" : \"tester\"");
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
