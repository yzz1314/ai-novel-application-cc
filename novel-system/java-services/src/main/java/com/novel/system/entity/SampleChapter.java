package com.novel.system.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
    name = "sample_chapters",
    indexes = {
        @Index(name = "idx_sample_chapters_sample_id", columnList = "sample_id"),
        @Index(name = "idx_sample_chapters_project_id", columnList = "project_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_sample_chapter_index", columnNames = {"sample_id", "chapter_index"})
    }
)
public class SampleChapter {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "sample_id", nullable = false, length = 64)
    private String sampleId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    @Column(length = 255)
    private String title;

    @Column(name = "start_offset")
    private Integer startOffset;

    @Column(name = "end_offset")
    private Integer endOffset;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
