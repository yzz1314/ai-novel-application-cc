package com.novel.system.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "samples")
public class Sample {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(length = 255)
    private String title;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "total_chars")
    private Integer totalChars;

    @Column(name = "total_chapters")
    private Integer totalChapters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SampleStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = "sample_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = SampleStatus.UPLOADED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum SampleStatus {
        UPLOADED,      // 已上传
        NORMALIZED,    // 已规范化
        CHUNKED,       // 已分块
        ANALYZED       // 已分析
    }
}
