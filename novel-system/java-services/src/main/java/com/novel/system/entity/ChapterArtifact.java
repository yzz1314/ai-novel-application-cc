package com.novel.system.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Entity
@Table(
    name = "chapter_artifacts",
    indexes = {
        @Index(name = "idx_chapter_artifacts_project_id", columnList = "project_id"),
        @Index(name = "idx_chapter_artifacts_book_id", columnList = "book_id"),
        @Index(name = "idx_chapter_artifacts_chapter", columnList = "book_id, volume_number, chapter_number"),
        @Index(name = "idx_chapter_artifacts_stage", columnList = "stage"),
        @Index(name = "idx_chapter_artifacts_updated_at", columnList = "updated_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_chapter_artifacts_project_book_chapter_stage",
            columnNames = {"project_id", "book_id", "volume_number", "chapter_number", "stage"}
        )
    }
)
public class ChapterArtifact {

    @Id
    @Column(length = 190)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "book_id", nullable = false, length = 96)
    private String bookId;

    @Column(name = "chapter_id", length = 128)
    private String chapterId;

    @Column(name = "volume_number", nullable = false)
    private Integer volumeNumber;

    @Column(name = "chapter_number", nullable = false)
    private Integer chapterNumber;

    @Column(name = "chapter_title", length = 255)
    private String chapterTitle;

    @Column(nullable = false, length = 24)
    private String stage;

    @Column(length = 48)
    private String status;

    @Column(name = "review_status", length = 48)
    private String reviewStatus;

    @Column(name = "human_review_status", length = 48)
    private String humanReviewStatus;

    private Integer version;

    @Column(name = "word_count")
    private Integer wordCount;

    @Column(name = "quality_score")
    private Double qualityScore;

    @Column(name = "needs_revision")
    private Boolean needsRevision;

    @Column(name = "boundary_passed")
    private Boolean boundaryPassed;

    @Column(name = "boundary_error_count")
    private Integer boundaryErrorCount;

    @Column(name = "boundary_warning_count")
    private Integer boundaryWarningCount;

    @Column(name = "chapter_path", length = 700)
    private String chapterPath;

    @Column(name = "text_path", length = 700)
    private String textPath;

    @Column(name = "context_pack_path", length = 700)
    private String contextPackPath;

    @Column(name = "source_draft_path", length = 700)
    private String sourceDraftPath;

    @Column(name = "final_path", length = 700)
    private String finalPath;

    @Column(name = "latest_review_path", length = 700)
    private String latestReviewPath;

    @Column(columnDefinition = "text")
    private String content;

    @Type(JsonType.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> chapter = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "boundary_check", columnDefinition = "jsonb")
    private Map<String, Object> boundaryCheck = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "revision_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> revisionHistory = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "human_review_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> humanReviewHistory = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "manual_edit_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> manualEditHistory = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "latest_review", columnDefinition = "jsonb")
    private Map<String, Object> latestReview = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "chapter_metadata", columnDefinition = "jsonb")
    private Map<String, Object> chapterMetadata = new LinkedHashMap<>();

    @Column(name = "chapter_created_at")
    private LocalDateTime chapterCreatedAt;

    @Column(name = "chapter_updated_at")
    private LocalDateTime chapterUpdatedAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
        syncedAt = syncedAt == null ? now : syncedAt;
        chapter = chapter == null ? new LinkedHashMap<>() : chapter;
        boundaryCheck = boundaryCheck == null ? new LinkedHashMap<>() : boundaryCheck;
        revisionHistory = revisionHistory == null ? new ArrayList<>() : revisionHistory;
        humanReviewHistory = humanReviewHistory == null ? new ArrayList<>() : humanReviewHistory;
        manualEditHistory = manualEditHistory == null ? new ArrayList<>() : manualEditHistory;
        latestReview = latestReview == null ? new LinkedHashMap<>() : latestReview;
        chapterMetadata = chapterMetadata == null ? new LinkedHashMap<>() : chapterMetadata;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
