package com.novel.system.dto.response;

import com.novel.system.entity.Project;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ProjectResponse {

    private String id;
    private String name;
    private String description;
    private String sampleGroupType;
    private String sourceLanguage;
    private String targetLanguage;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProjectResponse from(Project project) {
        ProjectResponse response = new ProjectResponse();
        response.setId(project.getId());
        response.setName(project.getName());
        response.setDescription(project.getDescription());
        response.setSampleGroupType(project.getSampleGroupType().name());
        response.setSourceLanguage(project.getSourceLanguage());
        response.setTargetLanguage(project.getTargetLanguage());
        response.setStatus(project.getStatus().name());
        response.setCreatedAt(project.getCreatedAt());
        response.setUpdatedAt(project.getUpdatedAt());
        return response;
    }
}
