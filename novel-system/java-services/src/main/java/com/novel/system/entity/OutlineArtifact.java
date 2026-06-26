package com.novel.system.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Entity
@Table(
    name = "outline_artifacts",
    indexes = {
        @Index(name = "idx_outline_artifacts_project_id", columnList = "project_id"),
        @Index(name = "idx_outline_artifacts_book_id", columnList = "book_id"),
        @Index(name = "idx_outline_artifacts_updated_at", columnList = "updated_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_outline_artifacts_project_book", columnNames = {"project_id", "book_id"})
    }
)
public class OutlineArtifact {

    @Id
    @Column(length = 160)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "book_id", nullable = false, length = 96)
    private String bookId;

    @Column(name = "book_title", length = 255)
    private String bookTitle;

    @Column(length = 128)
    private String genre;

    @Column(name = "target_word_count")
    private Integer targetWordCount;

    @Column(name = "total_volumes")
    private Integer totalVolumes;

    @Column(name = "total_chapters")
    private Integer totalChapters;

    @Column(name = "chapters_with_boundary")
    private Integer chaptersWithBoundary;

    @Column(length = 48)
    private String status;

    @Column(name = "review_status", length = 48)
    private String reviewStatus;

    @Column(name = "latest_review_score")
    private Integer latestReviewScore;

    @Column(name = "outline_path", length = 700)
    private String outlinePath;

    @Column(name = "project_soul_path", length = 700)
    private String projectSoulPath;

    @Column(name = "latest_review_path", length = 700)
    private String latestReviewPath;

    @Type(JsonType.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> outline = new LinkedHashMap<>();

    @Column(name = "project_soul", columnDefinition = "text")
    private String projectSoul;

    @Type(JsonType.class)
    @Column(name = "latest_review", columnDefinition = "jsonb")
    private Map<String, Object> latestReview = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "outline_metadata", columnDefinition = "jsonb")
    private Map<String, Object> outlineMetadata = new LinkedHashMap<>();

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
        outline = outline == null ? new LinkedHashMap<>() : outline;
        latestReview = latestReview == null ? new LinkedHashMap<>() : latestReview;
        outlineMetadata = outlineMetadata == null ? new LinkedHashMap<>() : outlineMetadata;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
