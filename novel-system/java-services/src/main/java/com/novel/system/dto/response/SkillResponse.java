package com.novel.system.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class SkillResponse {

    private String name;
    private String fileName;
    private String type;
    private String title;
    private String description;
    private String path;
    private Boolean enabled;
    private Integer priority;
    private List<String> scope;
    private List<String> conflicts;
    private List<Map<String, Object>> sourceTrace;
    private List<Map<String, Object>> evidenceItems;
    private String qualityStatus;
    private Integer qualityScore;
    private String qualityCheckedAt;
    private String latestQualityReportPath;
    private Map<String, Object> semanticQuality;
    private String approvalStatus;
    private String approvedAt;
    private String approvedBy;
    private String rejectedAt;
    private String rejectedBy;
    private String rejectionReason;
    private String latestApprovalReportPath;
    private Long sizeBytes;
    private LocalDateTime updatedAt;
    private String content;
}
