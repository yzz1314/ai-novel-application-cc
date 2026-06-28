package com.novel.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "access_role_policies")
public class AccessRolePolicy {

    @Id
    @Column(name = "action_key", length = 128)
    private String actionKey;

    @Column(length = 512)
    private String description;

    @Column(name = "allowed_roles", nullable = false, length = 1024)
    private String allowedRoles;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
