package com.novel.system.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Entity
@Table(
    name = "model_profiles",
    indexes = {
        @Index(name = "idx_model_profiles_default", columnList = "default_profile"),
        @Index(name = "idx_model_profiles_enabled", columnList = "enabled")
    }
)
public class ModelProfile {

    @Id
    @Column(name = "profile_id", length = 64)
    private String profileId;

    @Column(name = "profile_name", nullable = false, length = 255)
    private String profileName;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "default_profile", nullable = false)
    private Boolean defaultProfile = false;

    @Type(JsonType.class)
    @Column(name = "main_model", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> mainModel;

    @Type(JsonType.class)
    @Column(name = "fast_model", columnDefinition = "jsonb")
    private Map<String, Object> fastModel;

    @Type(JsonType.class)
    @Column(name = "embedding_model", columnDefinition = "jsonb")
    private Map<String, Object> embeddingModel;

    @Type(JsonType.class)
    @Column(name = "rerank_model", columnDefinition = "jsonb")
    private Map<String, Object> rerankModel;

    @Type(JsonType.class)
    @Column(name = "fallback_models", columnDefinition = "jsonb")
    private List<Map<String, Object>> fallbackModels;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
        enabled = enabled == null ? true : enabled;
        defaultProfile = defaultProfile == null ? false : defaultProfile;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
