package com.novel.system.dto.response;

import com.novel.system.entity.Sample;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SampleResponse {

    private String id;
    private String projectId;
    private String title;
    private String fileName;
    private Long fileSizeBytes;
    private Integer totalChars;
    private Integer totalChapters;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SampleResponse from(Sample sample) {
        SampleResponse response = new SampleResponse();
        response.setId(sample.getId());
        response.setProjectId(sample.getProjectId());
        response.setTitle(sample.getTitle());
        response.setFileName(sample.getFileName());
        response.setFileSizeBytes(sample.getFileSizeBytes());
        response.setTotalChars(sample.getTotalChars());
        response.setTotalChapters(sample.getTotalChapters());
        response.setStatus(sample.getStatus().name());
        response.setCreatedAt(sample.getCreatedAt());
        response.setUpdatedAt(sample.getUpdatedAt());
        return response;
    }
}
