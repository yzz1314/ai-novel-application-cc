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
    name = "memory_artifacts",
    indexes = {
        @Index(name = "idx_memory_artifacts_project_id", columnList = "project_id"),
        @Index(name = "idx_memory_artifacts_book_id", columnList = "book_id"),
        @Index(name = "idx_memory_artifacts_updated_at", columnList = "updated_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_memory_artifacts_project", columnNames = {"project_id"})
    }
)
public class MemoryArtifact {

    @Id
    @Column(length = 160)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "book_id", nullable = false, length = 96)
    private String bookId;

    @Column(length = 48)
    private String status;

    @Column(name = "character_count")
    private Integer characterCount;

    @Column(name = "world_setting_count")
    private Integer worldSettingCount;

    @Column(name = "plot_count")
    private Integer plotCount;

    @Column(name = "suspense_count")
    private Integer suspenseCount;

    @Column(name = "timeline_event_count")
    private Integer timelineEventCount;

    @Column(name = "markdown_count")
    private Integer markdownCount;

    @Column(name = "snapshot_count")
    private Integer snapshotCount;

    @Column(name = "memory_path", length = 700)
    private String memoryPath;

    @Column(name = "book_memory_path", length = 700)
    private String bookMemoryPath;

    @Column(name = "latest_snapshot_id", length = 160)
    private String latestSnapshotId;

    @Column(name = "latest_snapshot_path", length = 700)
    private String latestSnapshotPath;

    @Type(JsonType.class)
    @Column(name = "markdown_memories", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> markdownMemories = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "memory_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> memoryJson = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "latest_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> latestSnapshot = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> snapshots = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "latest_tasks", columnDefinition = "jsonb")
    private List<Map<String, Object>> latestTasks = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "memory_metadata", columnDefinition = "jsonb")
    private Map<String, Object> memoryMetadata = new LinkedHashMap<>();

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
        markdownMemories = markdownMemories == null ? new LinkedHashMap<>() : markdownMemories;
        memoryJson = memoryJson == null ? new LinkedHashMap<>() : memoryJson;
        latestSnapshot = latestSnapshot == null ? new LinkedHashMap<>() : latestSnapshot;
        snapshots = snapshots == null ? new ArrayList<>() : snapshots;
        latestTasks = latestTasks == null ? new ArrayList<>() : latestTasks;
        memoryMetadata = memoryMetadata == null ? new LinkedHashMap<>() : memoryMetadata;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
