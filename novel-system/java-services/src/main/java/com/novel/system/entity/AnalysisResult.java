package com.novel.system.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(
    name = "analysis_results",
    indexes = {
        @Index(name = "idx_analysis_results_project_id", columnList = "project_id"),
        @Index(name = "idx_analysis_results_sample_id", columnList = "sample_id"),
        @Index(name = "idx_analysis_results_chunk_id", columnList = "chunk_id"),
        @Index(name = "idx_analysis_results_status", columnList = "status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_analysis_result_chunk", columnNames = {"sample_id", "chunk_id"})
    }
)
public class AnalysisResult {

    @Id
    @Column(length = 160)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "sample_id", nullable = false, length = 64)
    private String sampleId;

    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "start_pos")
    private Integer startPos;

    @Column(name = "end_pos")
    private Integer endPos;

    @Column(name = "chapter_range", length = 255)
    private String chapterRange;

    @Column(length = 32)
    private String status;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "plot_function", columnDefinition = "text")
    private String plotFunction;

    @Column(name = "reader_hook", columnDefinition = "text")
    private String readerHook;

    @Column(name = "character_count")
    private Integer characterCount;

    @Column(name = "scene_technique_count")
    private Integer sceneTechniqueCount;

    @Column(name = "prose_technique_count")
    private Integer proseTechniqueCount;

    @Column(name = "outline_technique_count")
    private Integer outlineTechniqueCount;

    @Column(name = "appeal_point_count")
    private Integer appealPointCount;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> analysis;

    @Type(JsonType.class)
    @Column(name = "raw_result", columnDefinition = "jsonb")
    private Map<String, Object> rawResult;

    @Column(name = "analysis_path", length = 700)
    private String analysisPath;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = "SUCCESS";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
