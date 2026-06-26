package com.novel.system.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
    name = "sample_chunks",
    indexes = {
        @Index(name = "idx_sample_chunks_sample_id", columnList = "sample_id"),
        @Index(name = "idx_sample_chunks_project_id", columnList = "project_id"),
        @Index(name = "idx_sample_chunks_processed", columnList = "processed")
    }
)
public class SampleChunk {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "sample_id", nullable = false, length = 64)
    private String sampleId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "chapter_index")
    private Integer chapterIndex;

    @Column(name = "part_index")
    private Integer partIndex;

    @Column(name = "start_offset")
    private Integer startOffset;

    @Column(name = "end_offset")
    private Integer endOffset;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "heading_path", length = 500)
    private String headingPath;

    @Column(name = "file_path", length = 700)
    private String filePath;

    @Column(name = "processed")
    private Boolean processed = false;

    @Column(name = "analysis_path", length = 700)
    private String analysisPath;

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
