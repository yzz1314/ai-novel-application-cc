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
    name = "graph_artifacts",
    indexes = {
        @Index(name = "idx_graph_artifacts_project_id", columnList = "project_id"),
        @Index(name = "idx_graph_artifacts_book_id", columnList = "book_id"),
        @Index(name = "idx_graph_artifacts_updated_at", columnList = "updated_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_graph_artifacts_project_book", columnNames = {"project_id", "book_id"})
    }
)
public class GraphArtifact {

    @Id
    @Column(length = 200)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "book_id", nullable = false, length = 96)
    private String bookId;

    @Column(length = 48)
    private String status;

    @Column(name = "graph_id", length = 160)
    private String graphId;

    @Column(name = "node_count")
    private Integer nodeCount;

    @Column(name = "edge_count")
    private Integer edgeCount;

    @Column(name = "character_count")
    private Integer characterCount;

    @Column(name = "location_count")
    private Integer locationCount;

    @Column(name = "organization_count")
    private Integer organizationCount;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "average_degree")
    private Double averageDegree;

    @Column(name = "density")
    private Double density;

    @Column(name = "graph_path", length = 700)
    private String graphPath;

    @Type(JsonType.class)
    @Column(name = "graph_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> graphJson = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> nodes = new ArrayList<>();

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> edges = new ArrayList<>();

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> statistics = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "node_type_distribution", columnDefinition = "jsonb")
    private Map<String, Object> nodeTypeDistribution = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "edge_type_distribution", columnDefinition = "jsonb")
    private Map<String, Object> edgeTypeDistribution = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "top_nodes_by_degree", columnDefinition = "jsonb")
    private List<Map<String, Object>> topNodesByDegree = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "top_nodes_by_centrality", columnDefinition = "jsonb")
    private List<Map<String, Object>> topNodesByCentrality = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "top_nodes_by_betweenness", columnDefinition = "jsonb")
    private List<Map<String, Object>> topNodesByBetweenness = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "relationship_analysis", columnDefinition = "jsonb")
    private List<Map<String, Object>> relationshipAnalysis = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "key_paths", columnDefinition = "jsonb")
    private List<Map<String, Object>> keyPaths = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "incremental_summary", columnDefinition = "jsonb")
    private Map<String, Object> incrementalSummary = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "graph_analysis", columnDefinition = "jsonb")
    private Map<String, Object> graphAnalysis = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "latest_tasks", columnDefinition = "jsonb")
    private List<Map<String, Object>> latestTasks = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "graph_metadata", columnDefinition = "jsonb")
    private Map<String, Object> graphMetadata = new LinkedHashMap<>();

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
        graphJson = graphJson == null ? new LinkedHashMap<>() : graphJson;
        nodes = nodes == null ? new ArrayList<>() : nodes;
        edges = edges == null ? new ArrayList<>() : edges;
        statistics = statistics == null ? new LinkedHashMap<>() : statistics;
        nodeTypeDistribution = nodeTypeDistribution == null ? new LinkedHashMap<>() : nodeTypeDistribution;
        edgeTypeDistribution = edgeTypeDistribution == null ? new LinkedHashMap<>() : edgeTypeDistribution;
        topNodesByDegree = topNodesByDegree == null ? new ArrayList<>() : topNodesByDegree;
        topNodesByCentrality = topNodesByCentrality == null ? new ArrayList<>() : topNodesByCentrality;
        topNodesByBetweenness = topNodesByBetweenness == null ? new ArrayList<>() : topNodesByBetweenness;
        relationshipAnalysis = relationshipAnalysis == null ? new ArrayList<>() : relationshipAnalysis;
        keyPaths = keyPaths == null ? new ArrayList<>() : keyPaths;
        incrementalSummary = incrementalSummary == null ? new LinkedHashMap<>() : incrementalSummary;
        graphAnalysis = graphAnalysis == null ? new LinkedHashMap<>() : graphAnalysis;
        latestTasks = latestTasks == null ? new ArrayList<>() : latestTasks;
        graphMetadata = graphMetadata == null ? new LinkedHashMap<>() : graphMetadata;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
