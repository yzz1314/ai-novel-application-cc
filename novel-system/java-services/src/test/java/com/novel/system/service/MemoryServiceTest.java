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

    @Test
    void applyContinuityIssueFixAppendsMissingAppearanceAndMarksIssueResolved() throws Exception {
        writeProjectFile("memory/characters.json", """
            [
              {
                "character_id": "char_lin",
                "name": "Lin Yuan",
                "appearances": [1],
                "first_mentioned": 1,
                "last_updated": 1
              }
            ]
            """);
        writeProjectFile("memory/continuity/continuity_test.json", """
            {
              "project_id": "project_memory_versions",
              "book_id": "default",
              "chapter_id": "chapter_2",
              "chapter_number": 2,
              "has_issues": true,
              "issues": [
                {
                  "issue_id": "issue_missing_appearance",
                  "issue_type": "character",
                  "severity": "minor",
                  "title": "missing appearance",
                  "description": "appearances missing chapter 2",
                  "suggestion": "append chapter",
                  "fix": {
                    "action": "append_unique",
                    "memory_file": "characters.json",
                    "match": {
                      "character_id": "char_lin"
                    },
                    "field": "appearances",
                    "value": 2,
                    "sort": true
                  }
                }
              ]
            }
            """);

        Map<String, Object> response = memoryService.applyContinuityIssueFix(
            PROJECT_ID,
            "continuity_test",
            0,
            Map.of("actor", "tester", "note", "append missing appearance")
        );

        assertThat(response)
            .containsEntry("reportType", "continuity")
            .containsEntry("reportId", "continuity_test")
            .containsKey("beforeVersionId")
            .containsKey("eventPath");
        assertThat(Files.readString(projectRoot().resolve("memory/characters.json"), StandardCharsets.UTF_8))
            .contains("\"appearances\" : [ 1, 2 ]");

        String report = Files.readString(projectRoot().resolve("memory/continuity/continuity_test.json"), StandardCharsets.UTF_8);
        assertThat(report)
            .contains("\"resolution_status\" : \"resolved\"")
            .contains("\"fix_applied\" : true")
            .contains("\"changedCount\" : 1")
            .contains("\"action\" : \"append_unique\"");

        String beforeVersionPath = String.valueOf(response.get("beforeVersionPath"));
        assertThat(Files.readString(projectRoot().resolve(beforeVersionPath), StandardCharsets.UTF_8))
            .contains("\"reason\" : \"before_memory_continuity_fix\"")
            .contains("\\\"appearances\\\": [1]");

        String eventPath = String.valueOf(response.get("eventPath"));
        assertThat(Files.readString(projectRoot().resolve(eventPath), StandardCharsets.UTF_8))
            .contains("\"event_type\" : \"memory_continuity_fix\"")
            .contains("\"report_type\" : \"continuity\"")
            .contains("\"actor\" : \"tester\"");
    }

    @Test
    void applyContinuityIssueFixCanSetFirstMentioned() throws Exception {
        writeProjectFile("memory/world_settings.json", """
            [
              {
                "setting_id": "loc_fire",
                "name": "Fire City",
                "first_mentioned": 5,
                "status_changes": []
              }
            ]
            """);
        writeProjectFile("memory/continuity/continuity_setting_test.json", """
            {
              "project_id": "project_memory_versions",
              "book_id": "default",
              "chapter_id": "chapter_2",
              "chapter_number": 2,
              "has_issues": true,
              "issues": [
                {
                  "issue_id": "issue_setting_first_mentioned",
                  "issue_type": "setting",
                  "severity": "major",
                  "title": "setting appeared early",
                  "description": "setting first_mentioned is later than current chapter",
                  "suggestion": "set first_mentioned to current chapter after human confirmation",
                  "fix": {
                    "action": "set_field",
                    "memory_file": "world_settings.json",
                    "match": {
                      "setting_id": "loc_fire"
                    },
                    "field": "first_mentioned",
                    "value": 2
                  }
                }
              ]
            }
            """);

        Map<String, Object> response = memoryService.applyContinuityIssueFix(
            PROJECT_ID,
            "continuity_setting_test",
            0,
            Map.of("actor", "tester", "note", "confirm early setting")
        );

        assertThat(response)
            .containsEntry("reportType", "continuity")
            .containsEntry("reportId", "continuity_setting_test");
        assertThat(Files.readString(projectRoot().resolve("memory/world_settings.json"), StandardCharsets.UTF_8))
            .contains("\"first_mentioned\" : 2");

        String report = Files.readString(projectRoot().resolve("memory/continuity/continuity_setting_test.json"), StandardCharsets.UTF_8);
        assertThat(report)
            .contains("\"resolution_status\" : \"resolved\"")
            .contains("\"fix_applied\" : true")
            .contains("\"action\" : \"set_field\"")
            .contains("\"changedCount\" : 1");

        String beforeVersionPath = String.valueOf(response.get("beforeVersionPath"));
        assertThat(Files.readString(projectRoot().resolve(beforeVersionPath), StandardCharsets.UTF_8))
            .contains("\"reason\" : \"before_memory_continuity_fix\"")
            .contains("\\\"first_mentioned\\\": 5");

        String eventPath = String.valueOf(response.get("eventPath"));
        assertThat(Files.readString(projectRoot().resolve(eventPath), StandardCharsets.UTF_8))
            .contains("\"event_type\" : \"memory_continuity_fix\"")
            .contains("\"report_type\" : \"continuity\"");
    }

    @Test
    void applyAuditIssueFixCanSortTimelineByChapter() throws Exception {
        writeProjectFile("memory/timeline.json", """
            [
              {
                "event_id": "event_3",
                "title": "Later event",
                "chapter": 3
              },
              {
                "event_id": "event_1",
                "title": "Earlier event",
                "chapter": 1
              }
            ]
            """);
        writeProjectFile("memory/audits/memory_audit_timeline_sort.json", """
            {
              "project_id": "project_memory_versions",
              "book_id": "default",
              "issue_count": 1,
              "issues": [
                {
                  "issue_id": "issue_timeline_order",
                  "issue_type": "timeline",
                  "severity": "minor",
                  "title": "时间线排序倒挂",
                  "description": "timeline.json is not sorted by chapter",
                  "suggestion": "sort timeline by chapter",
                  "fix": {
                    "action": "sort_by_chapter",
                    "memory_file": "timeline.json",
                    "field": "chapter"
                  }
                }
              ]
            }
            """);

        Map<String, Object> response = memoryService.applyAuditIssueFix(
            PROJECT_ID,
            "memory_audit_timeline_sort",
            0,
            Map.of("actor", "tester", "note", "sort timeline")
        );

        assertThat(response)
            .containsEntry("reportType", "audit")
            .containsEntry("reportId", "memory_audit_timeline_sort")
            .containsKey("beforeVersionId")
            .containsKey("eventPath");
        String timeline = Files.readString(projectRoot().resolve("memory/timeline.json"), StandardCharsets.UTF_8);
        assertThat(timeline.indexOf("event_1")).isLessThan(timeline.indexOf("event_3"));

        String report = Files.readString(projectRoot().resolve("memory/audits/memory_audit_timeline_sort.json"), StandardCharsets.UTF_8);
        assertThat(report)
            .contains("\"resolution_status\" : \"resolved\"")
            .contains("\"fix_applied\" : true")
            .contains("\"action\" : \"sort_by_chapter\"")
            .contains("\"changedCount\" : 2");

        String beforeVersionPath = String.valueOf(response.get("beforeVersionPath"));
        assertThat(Files.readString(projectRoot().resolve(beforeVersionPath), StandardCharsets.UTF_8))
            .contains("\"reason\" : \"before_memory_audit_fix\"")
            .contains("\\\"event_id\\\": \\\"event_3\\\"");

        String eventPath = String.valueOf(response.get("eventPath"));
        assertThat(Files.readString(projectRoot().resolve(eventPath), StandardCharsets.UTF_8))
            .contains("\"event_type\" : \"memory_audit_fix\"")
            .contains("\"report_type\" : \"audit\"")
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
