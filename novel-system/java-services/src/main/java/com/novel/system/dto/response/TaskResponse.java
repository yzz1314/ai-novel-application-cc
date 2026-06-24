package com.novel.system.dto.response;

import com.novel.system.entity.Task;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TaskResponse {

    private String id;
    private String projectId;
    private String taskType;
    private String agentName;
    private String status;
    private Map<String, Object> inputRefs;
    private Map<String, Object> outputRefs;
    private Map<String, Object> result;
    private Map<String, Object> metrics;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static TaskResponse from(Task task) {
        TaskResponse response = new TaskResponse();
        response.setId(task.getId());
        response.setProjectId(task.getProjectId());
        response.setTaskType(task.getTaskType());
        response.setAgentName(task.getAgentName());
        response.setStatus(task.getStatus().name());
        response.setInputRefs(task.getInputRefs());
        response.setOutputRefs(task.getOutputRefs());
        response.setResult(task.getResult());
        response.setMetrics(task.getMetrics());
        response.setRetryCount(task.getRetryCount());
        response.setCreatedAt(task.getCreatedAt());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        return response;
    }
}
