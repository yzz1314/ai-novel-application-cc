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
    name = "skill_profiles",
    indexes = {
        @Index(name = "idx_skill_profiles_project_id", columnList = "project_id"),
        @Index(name = "idx_skill_profiles_updated_at", columnList = "updated_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_skill_profiles_project_name", columnNames = {"project_id", "name"})
    }
)
public class SkillProfile {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Type(JsonType.class)
    @Column(name = "enabled_skills", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> enabledSkills = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "task_overrides", columnDefinition = "jsonb")
    private Map<String, Object> taskOverrides = new LinkedHashMap<>();

    @Type(JsonType.class)
    @Column(name = "skill_metadata", columnDefinition = "jsonb")
    private Map<String, Object> skillMetadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
        enabledSkills = enabledSkills == null ? new ArrayList<>() : enabledSkills;
        taskOverrides = taskOverrides == null ? new LinkedHashMap<>() : taskOverrides;
        skillMetadata = skillMetadata == null ? new LinkedHashMap<>() : skillMetadata;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
