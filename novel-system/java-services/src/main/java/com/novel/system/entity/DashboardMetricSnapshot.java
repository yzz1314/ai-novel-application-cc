package com.novel.system.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Entity
@Table(name = "dashboard_metric_snapshots")
public class DashboardMetricSnapshot {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "health_status", length = 48)
    private String healthStatus;

    @Column(name = "active_tasks")
    private Long activeTasks;

    @Column(name = "failed_tasks")
    private Long failedTasks;

    @Column(name = "waiting_approvals")
    private Long waitingApprovals;

    @Column(name = "slow_task_count")
    private Long slowTaskCount;

    @Column(name = "high_retry_task_count")
    private Long highRetryTaskCount;

    @Column(name = "active_alert_count")
    private Long activeAlertCount;

    @Column(name = "snoozed_alert_count")
    private Long snoozedAlertCount;

    @Column(name = "acknowledged_alert_count")
    private Long acknowledgedAlertCount;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "avg_duration_ms")
    private Long avgDurationMs;

    @Column(name = "max_duration_ms")
    private Long maxDurationMs;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics = new LinkedHashMap<>();

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = "dashboard_snapshot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        capturedAt = capturedAt == null ? LocalDateTime.now() : capturedAt;
        metrics = metrics == null ? new LinkedHashMap<>() : metrics;
    }
}
