package com.novel.system.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @Column(name = "agent_name", length = 100)
    private String agentName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Type(JsonType.class)
    @Column(name = "input_refs", columnDefinition = "jsonb")
    private Map<String, Object> inputRefs;

    @Type(JsonType.class)
    @Column(name = "output_refs", columnDefinition = "jsonb")
    private Map<String, Object> outputRefs;

    @Type(JsonType.class)
    @Column(name = "parameters", columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    @Type(JsonType.class)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Type(JsonType.class)
    @Column(name = "errors", columnDefinition = "jsonb")
    private Map<String, Object> errors;

    @Type(JsonType.class)
    @Column(name = "warnings", columnDefinition = "jsonb")
    private Map<String, Object> warnings;

    @Type(JsonType.class)
    @Column(name = "metrics", columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    @Column(name = "checkpoint_ref", length = 500)
    private String checkpointRef;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    private Integer maxRetries = 3;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = TaskStatus.PENDING;
        }
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }
}
