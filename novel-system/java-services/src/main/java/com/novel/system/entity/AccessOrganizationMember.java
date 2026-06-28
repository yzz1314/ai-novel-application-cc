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
@Table(name = "access_organization_members")
public class AccessOrganizationMember {

    @Id
    @Column(length = 200)
    private String id;

    @Column(name = "organization_id", nullable = false, length = 128)
    private String organizationId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false, length = 64)
    private String role;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "granted_by", length = 128)
    private String grantedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null || id.isBlank()) {
            id = organizationId + "_" + userId;
        }
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
