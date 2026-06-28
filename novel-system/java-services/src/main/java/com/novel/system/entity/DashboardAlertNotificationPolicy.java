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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Entity
@Table(name = "dashboard_alert_notification_policies")
public class DashboardAlertNotificationPolicy {

    @Id
    @Column(length = 80)
    private String id = "default";

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "default_group", length = 80)
    private String defaultGroup = "ops";

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> subscribers = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "routing_rules", columnDefinition = "jsonb")
    private Map<String, Object> routingRules = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> templates = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> channels = new LinkedHashMap<>();

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        id = id == null || id.isBlank() ? "default" : id;
        enabled = enabled == null ? true : enabled;
        defaultGroup = defaultGroup == null || defaultGroup.isBlank() ? "ops" : defaultGroup;
        subscribers = subscribers == null ? new ArrayList<>() : subscribers;
        routingRules = routingRules == null ? new LinkedHashMap<>() : routingRules;
        templates = templates == null ? new LinkedHashMap<>() : templates;
        channels = channels == null ? new LinkedHashMap<>() : channels;
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
    }

    @PreUpdate
    protected void onUpdate() {
        enabled = enabled == null ? true : enabled;
        defaultGroup = defaultGroup == null || defaultGroup.isBlank() ? "ops" : defaultGroup;
        subscribers = subscribers == null ? new ArrayList<>() : subscribers;
        routingRules = routingRules == null ? new LinkedHashMap<>() : routingRules;
        templates = templates == null ? new LinkedHashMap<>() : templates;
        channels = channels == null ? new LinkedHashMap<>() : channels;
        updatedAt = LocalDateTime.now();
    }
}
