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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void outlineGovernanceApprovesLocksAndProtectsOutlineEdits() throws Exception {
        writeProjectFile("novel/outline/book_1_outline.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Original Outline",
              "genre": "玄幻",
              "target_word_count": 100000,
              "total_volumes": 1,
              "total_chapters": 1,
              "volumes": [
                {
                  "volume_number": 1,
                  "volume_title": "Volume One",
                  "chapters": [
                    {
                      "chapter_number": 1,
                      "chapter_title": "Boundary Ready",
                      "core_goal": "Open the case",
                      "must_write": ["trial gate"],
                      "allowed_progress": ["find the token"],
                      "must_not_write": ["final culprit"],
                      "reserved_for_future": ["city conspiracy"],
                      "stop_point": "door opens",
                      "ending_hook": "a hidden lamp burns"
                    }
                  ]
                }
              ]
            }
            """);
        when(outlineArtifactService.syncOutlineFromWorkspace(eq(PROJECT_ID), eq(BOOK_ID)))
            .thenReturn(Map.of("status", "synced"));

        Map<String, Object> approved = bookArtifactService.updateOutlineGovernance(
            PROJECT_ID,
            BOOK_ID,
            "approve",
            Map.of("actor", "tester", "note", "ready", "lock", true)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> governance = (Map<String, Object>) approved.get("governance");
        assertThat(governance)
            .containsEntry("approvalStatus", "approved")
            .containsEntry("locked", true)
            .containsEntry("approvedBy", "tester");

        assertThatThrownBy(() -> bookArtifactService.updateOutline(
            PROJECT_ID,
            BOOK_ID,
            Map.of(
                "outline",
                Map.of(
                    "bookTitle", "Blocked Outline",
                    "genre", "玄幻",
                    "targetWordCount", 100000,
                    "totalVolumes", 1,
                    "totalChapters", 1,
                    "volumes", List.of()
                )
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Outline is locked");

        bookArtifactService.updateOutlineGovernance(PROJECT_ID, BOOK_ID, "unlock", Map.of("actor", "tester"));
        Map<String, Object> response = bookArtifactService.updateOutline(
            PROJECT_ID,
            BOOK_ID,
            Map.of(
                "editor", "tester",
                "editNote", "outline edit after unlock",
                "outline",
                Map.of(
                    "bookTitle", "Unlocked Outline",
                    "genre", "玄幻",
                    "targetWordCount", 100000,
                    "totalVolumes", 1,
                    "totalChapters", 1,
                    "volumes", List.of()
                )
            )
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> outline = (Map<String, Object>) response.get("outline");
        @SuppressWarnings("unchecked")
        Map<String, Object> outlineGovernance = (Map<String, Object>) outline.get("outlineGovernance");
        assertThat(outline)
            .containsEntry("bookTitle", "Unlocked Outline");
        assertThat(outlineGovernance)
            .containsEntry("approvalStatus", "pending_review")
            .containsEntry("locked", false)
            .containsEntry("lastEditedBy", "tester");

        String meta = Files.readString(projectRoot().resolve("novel/outline/book_1_outline_meta.json"));
        assertThat(meta)
            .contains("\"approval_status\" : \"pending_review\"")
            .contains("\"last_edited_by\" : \"tester\"");
    }

    @Test
    void outlineApprovalRequiresBoundaryFieldsUnlessOverridden() throws Exception {
        writeProjectFile("novel/outline/book_1_outline.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Incomplete Boundary Outline",
              "genre": "xuanhuan",
              "target_word_count": 100000,
              "total_volumes": 1,
              "total_chapters": 1,
              "volumes": [
                {
                  "volume_number": 1,
                  "chapters": [
                    {
                      "chapter_number": 1,
                      "chapter_title": "Missing Boundary",
                      "core_goal": "Open the case"
                    }
                  ]
                }
              ]
            }
            """);

        assertThatThrownBy(() -> bookArtifactService.updateOutlineGovernance(
            PROJECT_ID,
            BOOK_ID,
            "approve",
            Map.of("actor", "tester", "lock", false)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("boundary");

        Map<String, Object> approved = bookArtifactService.updateOutlineGovernance(
            PROJECT_ID,
            BOOK_ID,
            "approve",
            Map.of("actor", "tester", "lock", false, "overrideOutlineApproval", true)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> governance = (Map<String, Object>) approved.get("governance");
        @SuppressWarnings("unchecked")
        Map<String, Object> approvalCheck = (Map<String, Object>) governance.get("approvalCheck");
        assertThat(governance)
            .containsEntry("approvalStatus", "approved")
            .containsEntry("approvalOverride", true)
            .containsEntry("locked", false);
        assertThat(approvalCheck)
            .containsEntry("status", "override")
            .containsEntry("boundaryComplete", false)
            .containsEntry("issueCount", 1);

        String meta = Files.readString(projectRoot().resolve("novel/outline/book_1_outline_meta.json"));
        assertThat(meta)
            .contains("\"approval_override\" : true")
            .contains("\"missing_boundary_fields\"");
    }

    @Test
    void restoresOutlineVersionWithSnapshotAndPendingReviewGovernance() throws Exception {
        writeProjectFile("novel/outline/book_1_outline.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Current Outline",
              "genre": "玄幻",
              "target_word_count": 100000,
              "total_volumes": 1,
              "total_chapters": 1,
              "volumes": []
            }
            """);
        writeProjectFile("novel/outline/versions/book_1/book_1_outline_20260626010101000.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Archived Outline",
              "genre": "玄幻",
              "target_word_count": 120000,
              "total_volumes": 1,
              "total_chapters": 2,
              "archived_at": "2026-06-26T01:01:01",
              "archive_reason": "before_outline_edit",
              "volumes": []
            }
            """);
        when(outlineArtifactService.syncOutlineFromWorkspace(eq(PROJECT_ID), eq(BOOK_ID)))
            .thenReturn(Map.of("status", "synced"));

        List<Map<String, Object>> versions = bookArtifactService.listOutlineVersions(PROJECT_ID, BOOK_ID);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0))
            .containsEntry("id", "book_1_outline_20260626010101000")
            .containsEntry("bookTitle", "Archived Outline")
            .containsEntry("archiveReason", "before_outline_edit");

        Map<String, Object> detail = bookArtifactService.getOutlineVersion(
            PROJECT_ID,
            BOOK_ID,
            "book_1_outline_20260626010101000"
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> detailOutline = (Map<String, Object>) detail.get("outline");
        assertThat(detailOutline)
            .containsEntry("bookTitle", "Archived Outline")
            .containsEntry("totalChapters", 2);

        Map<String, Object> response = bookArtifactService.restoreOutlineVersion(
            PROJECT_ID,
            BOOK_ID,
            "book_1_outline_20260626010101000",
            Map.of("actor", "tester", "note", "restore outline")
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> outline = (Map<String, Object>) response.get("outline");
        assertThat(response)
            .containsEntry("status", "restored")
            .containsEntry("restoredFromVersionId", "book_1_outline_20260626010101000");
        assertThat(outline)
            .containsEntry("bookTitle", "Archived Outline")
            .containsEntry("restoredBy", "tester");
        assertThat(Files.readString(projectRoot().resolve("novel/outline/book_1_outline.json")))
            .contains("Archived Outline")
            .doesNotContain("archived_at")
            .doesNotContain("archive_reason");

        String previousSnapshotPath = String.valueOf(response.get("previousSnapshotPath"));
        assertThat(previousSnapshotPath).startsWith("novel/outline/versions/book_1/book_1_outline_");
        assertThat(Files.readString(projectRoot().resolve(previousSnapshotPath)))
            .contains("Current Outline")
            .contains("before_outline_restore");

        String meta = Files.readString(projectRoot().resolve("novel/outline/book_1_outline_meta.json"));
        assertThat(meta)
            .contains("\"approval_status\" : \"pending_review\"")
            .contains("\"restored_from_version_id\"")
            .contains("book_1_outline_20260626010101000");
    }

    @Test
    void lockedOutlineRejectsRestoreWithoutOverride() throws Exception {
        writeProjectFile("novel/outline/book_1_outline.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Locked Current Outline",
              "genre": "玄幻",
              "target_word_count": 100000,
              "total_volumes": 1,
              "total_chapters": 1,
              "volumes": []
            }
            """);
        writeProjectFile("novel/outline/book_1_outline_meta.json", """
            {
              "book_id": "book_1",
              "locked": true,
              "approval_status": "approved"
            }
            """);
        writeProjectFile("novel/outline/versions/book_1/book_1_outline_20260626010101000.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Archived Outline",
              "genre": "玄幻",
              "target_word_count": 120000,
              "total_volumes": 1,
              "total_chapters": 2,
              "volumes": []
            }
            """);

        assertThatThrownBy(() -> bookArtifactService.restoreOutlineVersion(
            PROJECT_ID,
            BOOK_ID,
            "book_1_outline_20260626010101000",
            Map.of("actor", "tester")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("locked");

        assertThat(Files.readString(projectRoot().resolve("novel/outline/book_1_outline.json")))
            .contains("Locked Current Outline");
    }

    @Test
    void reviewOutlineChapterWritesStatusHistorySnapshotAndReport() throws Exception {
        writeProjectFile("novel/outline/book_1_outline.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Chapter Review Outline",
              "genre": "xuanhuan",
              "target_word_count": 100000,
              "total_volumes": 1,
              "total_chapters": 2,
              "volumes": [
                {
                  "volume_number": 1,
                  "volume_title": "Volume One",
                  "chapters": [
                    {
                      "chapter_number": 1,
                      "chapter_title": "First Gate",
                      "plot_goal": "open the first gate",
                      "core_goal": "Open the case",
                      "must_write": ["trial gate"],
                      "allowed_progress": ["find the token"],
                      "must_not_write": ["final culprit"],
                      "reserved_for_future": {"chapter_2": "city conspiracy"},
                      "stop_point": "door opens",
                      "ending_hook": "a hidden lamp burns"
                    },
                    {
                      "chapter_number": 2,
                      "chapter_title": "Second Gate",
                      "plot_goal": "enter the city"
                    }
                  ]
                }
              ]
            }
            """);
        when(outlineArtifactService.syncOutlineFromWorkspace(eq(PROJECT_ID), eq(BOOK_ID)))
            .thenReturn(Map.of("status", "synced"));

        Map<String, Object> response = bookArtifactService.reviewOutlineChapter(
            PROJECT_ID,
            BOOK_ID,
            1,
            1,
            Map.of(
                "decision", "approved",
                "reviewer", "outline-lead",
                "feedback", "boundary ready",
                "note", "approve chapter outline"
            )
        );

        assertThat(response)
            .containsEntry("decision", "approved")
            .containsEntry("reviewer", "outline-lead")
            .containsEntry("volumeNumber", 1)
            .containsEntry("chapterNumber", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.get("reviewSummary");
        assertThat(summary)
            .containsEntry("status", "pending_review")
            .containsEntry("totalChapters", 2)
            .containsEntry("approvedCount", 1)
            .containsEntry("pendingCount", 1);

        String snapshotPath = String.valueOf(response.get("snapshotPath"));
        String reportPath = String.valueOf(response.get("reportPath"));
        assertThat(snapshotPath).startsWith("novel/outline/versions/book_1/book_1_outline_");
        assertThat(reportPath).startsWith("novel/reviews/book_1/outline/outline_chapter_review_");
        assertThat(Files.readString(projectRoot().resolve(snapshotPath))).contains("Chapter Review Outline");

        String outline = Files.readString(projectRoot().resolve("novel/outline/book_1_outline.json"));
        assertThat(outline)
            .contains("\"outline_review_status\" : \"approved\"")
            .contains("\"outline_reviewer\" : \"outline-lead\"")
            .contains("\"outline_review_history\"");

        String report = Files.readString(projectRoot().resolve(reportPath));
        assertThat(report)
            .contains("\"review_type\" : \"outline_chapter_review\"")
            .contains("\"decision\" : \"approved\"")
            .contains("\"approvedCount\" : 1");

        String meta = Files.readString(projectRoot().resolve("novel/outline/book_1_outline_meta.json"));
        assertThat(meta)
            .contains("\"chapter_review_status\" : \"pending_review\"")
            .contains("\"last_chapter_reviewed_by\" : \"outline-lead\"");
    }

    @Test
    void lockedOutlineRejectsChapterReviewWithoutOverride() throws Exception {
        writeProjectFile("novel/outline/book_1_outline.json", """
            {
              "project_id": "project_soul_restore",
              "book_id": "book_1",
              "book_title": "Locked Chapter Review Outline",
              "volumes": [
                {
                  "volume_number": 1,
                  "chapters": [
                    {
                      "chapter_number": 1,
                      "chapter_title": "Locked Chapter"
                    }
                  ]
                }
              ]
            }
            """);
        writeProjectFile("novel/outline/book_1_outline_meta.json", """
            {
              "book_id": "book_1",
              "locked": true,
              "approval_status": "approved"
            }
            """);

        assertThatThrownBy(() -> bookArtifactService.reviewOutlineChapter(
            PROJECT_ID,
            BOOK_ID,
            1,
            1,
            Map.of("decision", "approved", "reviewer", "tester")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("locked");

        assertThat(Files.readString(projectRoot().resolve("novel/outline/book_1_outline.json")))
            .doesNotContain("outline_review_status");
    }

    @Test
    void diffsDraftAndFinalChapterWithSummaryAndLineHunks() throws Exception {
        writeChapterFile("novel/chapters/drafts/book_1/volume_1/chapter_2.json", """
            {
              "book_id": "book_1",
              "volume_number": 1,
              "chapter_number": 2,
              "chapter_id": "chapter_2",
              "chapter_title": "Draft Title",
              "stage": "draft",
              "version": 1,
              "word_count": 17,
              "content": "same line\\ndraft only\\nold conflict"
            }
            """);
        writeChapterFile("novel/chapters/final/book_1/volume_1/chapter_2.json", """
            {
              "book_id": "book_1",
              "volume_number": 1,
              "chapter_number": 2,
              "chapter_id": "chapter_2",
              "chapter_title": "Final Title",
              "stage": "final",
              "version": 2,
              "word_count": 19,
              "content": "same line\\nfinal only\\nnew conflict"
            }
            """);

        Map<String, Object> response = bookArtifactService.diffChapter(
            PROJECT_ID,
            BOOK_ID,
            1,
            2,
            Map.of()
        );

        assertThat(response)
            .containsEntry("bookId", BOOK_ID)
            .containsEntry("volumeNumber", 1)
            .containsEntry("chapterNumber", 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) response.get("from");
        @SuppressWarnings("unchecked")
        Map<String, Object> to = (Map<String, Object>) response.get("to");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hunks = (List<Map<String, Object>>) response.get("hunks");

        assertThat(from)
            .containsEntry("kind", "stage")
            .containsEntry("stage", "draft")
            .containsEntry("chapterTitle", "Draft Title")
            .containsEntry("path", "novel/chapters/drafts/book_1/volume_1/chapter_2.json");
        assertThat(to)
            .containsEntry("kind", "stage")
            .containsEntry("stage", "final")
            .containsEntry("chapterTitle", "Final Title")
            .containsEntry("path", "novel/chapters/final/book_1/volume_1/chapter_2.json");
        assertThat(summary)
            .containsEntry("titleChanged", true)
            .containsEntry("contentChanged", true)
            .containsEntry("wordCountDelta", 2)
            .containsEntry("changedLines", 2L)
            .containsEntry("unchangedLines", 1L);
        assertThat(hunks).extracting(item -> item.get("type"))
            .containsExactly("equal", "changed", "changed");
    }

    @Test
    void diffsChapterVersionAgainstFinalChapter() throws Exception {
        writeChapterFile("novel/chapters/final/book_1/volume_1/chapter_3.json", """
            {
              "book_id": "book_1",
              "volume_number": 1,
              "chapter_number": 3,
              "chapter_id": "chapter_3",
              "chapter_title": "Final Version",
              "stage": "final",
              "version": 4,
              "word_count": 18,
              "content": "line A\\nline B final"
            }
            """);
        writeChapterFile("novel/chapters/versions/book_1/volume_1/chapter_3_v2_20260627010101000.json", """
            {
              "book_id": "book_1",
              "volume_number": 1,
              "chapter_number": 3,
              "chapter_id": "chapter_3",
              "chapter_title": "Archived Version",
              "stage": "draft",
              "version": 2,
              "word_count": 19,
              "source_path": "novel/chapters/drafts/book_1/volume_1/chapter_3.json",
              "content": "line A\\nline B archived"
            }
            """);

        Map<String, Object> response = bookArtifactService.diffChapter(
            PROJECT_ID,
            BOOK_ID,
            1,
            3,
            Map.of(
                "fromVersionId", "chapter_3_v2_20260627010101000",
                "toStage", "final"
            )
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) response.get("from");
        @SuppressWarnings("unchecked")
        Map<String, Object> to = (Map<String, Object>) response.get("to");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.get("summary");

        assertThat(from)
            .containsEntry("kind", "version")
            .containsEntry("versionId", "chapter_3_v2_20260627010101000")
            .containsEntry("stage", "draft");
        assertThat(to)
            .containsEntry("kind", "stage")
            .containsEntry("stage", "final");
        assertThat(summary)
            .containsEntry("titleChanged", true)
            .containsEntry("contentChanged", true)
            .containsEntry("wordCountDelta", -1)
            .containsEntry("changedLines", 1L);
    }

    @Test
    void batchFinalizeChaptersSkipsExistingFinalAndContinuesFailures() throws Exception {
        writeChapterFile("novel/chapters/drafts/book_1/volume_1/chapter_1.json", """
            {
              "book_id": "book_1",
              "volume_number": 1,
              "chapter_number": 1,
              "chapter_id": "chapter_1",
              "chapter_title": "Draft One",
              "stage": "draft",
              "version": 1,
              "word_count": 9,
              "content": "chapter one"
            }
            """);
        writeChapterFile("novel/chapters/final/book_1/volume_1/chapter_2.json", """
            {
              "book_id": "book_1",
              "volume_number": 1,
              "chapter_number": 2,
              "chapter_id": "chapter_2",
              "chapter_title": "Final Two",
              "stage": "final",
              "version": 2,
              "word_count": 9,
              "content": "chapter two"
            }
            """);
        when(chapterArtifactService.syncChapterFromWorkspace(eq(PROJECT_ID), eq(BOOK_ID), eq(1), eq(1), eq("final")))
            .thenReturn(Map.of("status", "synced"));

        Map<String, Object> response = bookArtifactService.finalizeChapters(
            PROJECT_ID,
            BOOK_ID,
            Map.of(
                "chapters",
                List.of(
                    Map.of("volumeNumber", 1, "chapterNumber", 1),
                    Map.of("volumeNumber", 1, "chapterNumber", 2),
                    Map.of("volumeNumber", 1, "chapterNumber", 3)
                ),
                "triggerMemoryExtraction", false,
                "continueOnError", true,
                "skipFinal", true,
                "finalizer", "batch-test",
                "finalizeNote", "batch publish"
            )
        );

        assertThat(response)
            .containsEntry("requestedCount", 3)
            .containsEntry("finalizedCount", 1L)
            .containsEntry("skippedCount", 1L)
            .containsEntry("failedCount", 1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).extracting(item -> item.get("status"))
            .containsExactly("finalized", "skipped", "failed");
        assertThat(Files.exists(projectRoot().resolve("novel/chapters/final/book_1/volume_1/chapter_1.json"))).isTrue();
        assertThat(String.valueOf(response.get("reportPath"))).startsWith("novel/reviews/book_1/batch/chapter_batch_finalize_");
        String report = Files.readString(projectRoot().resolve(String.valueOf(response.get("reportPath"))));
        assertThat(report)
            .contains("\"review_type\" : \"chapter_batch_finalize\"")
            .contains("\"finalized_count\" : 1")
            .contains("\"skipped_count\" : 1")
            .contains("\"failed_count\" : 1");
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

    private Path writeChapterFile(String relativePath, String content) throws Exception {
        return writeProjectFile(relativePath, content);
    }
}
