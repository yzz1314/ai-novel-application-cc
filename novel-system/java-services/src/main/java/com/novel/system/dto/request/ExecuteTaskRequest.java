package com.novel.system.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class ExecuteTaskRequest {

    @NotBlank(message = "Agent名称不能为空")
    private String agentName;

    private String taskType;

    private Map<String, Object> inputRefs = new HashMap<>();

    private Map<String, Object> parameters = new HashMap<>();

    private Map<String, Object> config = new HashMap<>();

    public String resolvedTaskType() {
        return taskType != null && !taskType.isBlank() ? taskType : agentName;
    }

    public Map<String, Object> resolvedParameters() {
        return parameters != null && !parameters.isEmpty() ? parameters : config;
    }
}
