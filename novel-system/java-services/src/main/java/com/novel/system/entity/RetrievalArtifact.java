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
    name = "retrieval_artifacts",
    indexes = {
        @Index(name = "idx_retrieval_artifacts_project_id", columnList = "project_id"),
        @Index(name = "idx_retrieval_artifacts_updated_at", columnList = "updated_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_retrieval_artifacts_project", columnNames = {"project_id"})
    }
)
public class RetrievalArtifact {

    @Id
    @Column(length = 160)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(length = 48)
    private String status;

    @Column(name = "bm25_document_count")
    private Integer bm25DocumentCount;

    @Column(name = "vector_document_count")
    private Integer vectorDocumentCount;

    @Column(name = "hybrid_document_count")
    private Integer hybridDocumentCount;

    @Column(name = "context_pack_count")
    private Integer contextPackCount;

    @Column(name = "config_path", length = 700)
    private String configPath;

    @Column(name = "rebuild_report_path", length = 700)
    private String rebuildReportPath;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "bm25_summary", columnDefinition = "jsonb")
    private Map<String, Object> bm25Summary = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "vector_summary", columnDefinition = "jsonb")
    private Map<String, Object> vectorSummary = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "hybrid_summary", columnDefinition = "jsonb")
    private Map<String, Object> hybridSummary = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "rebuild_report", columnDefinition = "jsonb")
    private Map<String, Object> rebuildReport = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "context_packs", columnDefinition = "jsonb")
    private List<Map<String, Object>> contextPacks = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "latest_tasks", columnDefinition = "jsonb")
    private List<Map<String, Object>> latestTasks = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "retrieval_metadata", columnDefinition = "jsonb")
    private Map<String, Object> retrievalMetadata = new LinkedHashMap<>();

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
        config = config == null ? new LinkedHashMap<>() : config;
        bm25Summary = bm25Summary == null ? new LinkedHashMap<>() : bm25Summary;
        vectorSummary = vectorSummary == null ? new LinkedHashMap<>() : vectorSummary;
        hybridSummary = hybridSummary == null ? new LinkedHashMap<>() : hybridSummary;
        rebuildReport = rebuildReport == null ? new LinkedHashMap<>() : rebuildReport;
        contextPacks = contextPacks == null ? new ArrayList<>() : contextPacks;
        latestTasks = latestTasks == null ? new ArrayList<>() : latestTasks;
        retrievalMetadata = retrievalMetadata == null ? new LinkedHashMap<>() : retrievalMetadata;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
