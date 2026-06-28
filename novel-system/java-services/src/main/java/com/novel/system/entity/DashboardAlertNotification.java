package com.novel.system.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Entity
@Table(name = "dashboard_alert_notifications")
public class DashboardAlertNotification {

    @Id
    @Column(length = 80)
    private String id;

    @Column(name = "alert_id", nullable = false, length = 120)
    private String alertId;

    @Column(name = "condition_key", nullable = false, length = 240)
    private String conditionKey;

    @Column(length = 32)
    private String severity;

    @Column(name = "escalation_level", length = 32)
    private String escalationLevel;

    @Column(length = 32)
    private String status;

    @Column(name = "notification_count")
    private Long notificationCount = 1L;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> channels = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (id == null) {
            id = "alert_notification_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        createdAt = createdAt == null ? now : createdAt;
        lastSeenAt = lastSeenAt == null ? now : lastSeenAt;
        status = status == null ? "RECORDED" : status;
        notificationCount = notificationCount == null || notificationCount < 1 ? 1L : notificationCount;
        channels = channels == null ? new LinkedHashMap<>() : channels;
        payload = payload == null ? new LinkedHashMap<>() : payload;
    }

    @PreUpdate
    protected void onUpdate() {
        lastSeenAt = lastSeenAt == null ? LocalDateTime.now() : lastSeenAt;
        channels = channels == null ? new LinkedHashMap<>() : channels;
        payload = payload == null ? new LinkedHashMap<>() : payload;
    }
}
