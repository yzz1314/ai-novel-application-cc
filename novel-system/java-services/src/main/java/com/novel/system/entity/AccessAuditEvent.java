package com.novel.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "access_audit_events")
public class AccessAuditEvent {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "actor_id", length = 128)
    private String actorId;

    @Column(name = "actor_name", length = 255)
    private String actorName;

    @Column(name = "organization_id", length = 128)
    private String organizationId;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @Column(name = "target_user_id", length = 128)
    private String targetUserId;

    @Column(name = "target_organization_id", length = 128)
    private String targetOrganizationId;

    @Column(length = 128)
    private String action;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(length = 1024)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null || id.isBlank()) {
            id = "audit_" + UUID.randomUUID().toString().replace("-", "");
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
