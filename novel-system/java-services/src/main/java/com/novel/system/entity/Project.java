package com.novel.system.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "sample_group_type", nullable = false, length = 20)
    private SampleGroupType sampleGroupType;

    @Column(name = "source_language", length = 10)
    private String sourceLanguage = "zh-CN";

    @Column(name = "target_language", length = 10)
    private String targetLanguage = "zh-CN";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Column(name = "model_profile_id", length = 64)
    private String modelProfileId;

    @Column(name = "skill_profile_id", length = 64)
    private String skillProfileId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = generateProjectId();
        }
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = ProjectStatus.CREATED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    private String generateProjectId() {
        // 格式: YYYYMMDD_HHMMSS_name_hash
        String timestamp = LocalDateTime.now().toString()
            .replaceAll("[^0-9]", "")
            .substring(0, 14);
        String nameHash = Integer.toHexString(name.hashCode()).substring(0, 4);
        return String.format("%s_%s_%s",
            timestamp.substring(0, 8),
            timestamp.substring(8, 14),
            nameHash);
    }

    public enum SampleGroupType {
        SAME_AUTHOR,
        SAME_GENRE,
        MIXED
    }

    public enum ProjectStatus {
        CREATED,
        INGESTING,
        ANALYZED,
        OUTLINING,
        WRITING,
        ARCHIVED
    }
}
