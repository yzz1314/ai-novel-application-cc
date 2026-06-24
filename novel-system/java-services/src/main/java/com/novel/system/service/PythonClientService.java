package com.novel.system.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PythonClientService {

    private final RestTemplate restTemplate;

    @Value("${python.service.url:http://python-service:8000}")
    private String pythonServiceUrl;

    /**
     * 调用Python Agent执行任务
     */
    public Map<String, Object> callAgent(String agentName, Map<String, Object> request) {
        String url = pythonServiceUrl + "/api/agents/" + agentName + "/run";

        log.info("Calling Python agent: {} at {}", agentName, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("Agent call successful: {}", agentName);
                return response.getBody();
            } else {
                throw new RuntimeException("Agent调用失败，状态码: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to call agent: {}", agentName, e);
            throw new RuntimeException("调用Python服务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取消任务
     */
    public void cancelTask(String taskId) {
        String url = pythonServiceUrl + "/api/tasks/" + taskId + "/cancel";

        log.info("Cancelling task: {}", taskId);

        try {
            restTemplate.postForEntity(url, null, Void.class);
        } catch (Exception e) {
            log.error("Failed to cancel task: {}", taskId, e);
            throw new RuntimeException("取消任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 健康检查
     */
    public boolean checkHealth() {
        try {
            String url = pythonServiceUrl + "/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.error("Python service health check failed", e);
            return false;
        }
    }

    /**
     * 构建Agent请求
     */
    public Map<String, Object> buildAgentRequest(
            String taskId,
            String projectId,
            String taskType,
            Map<String, Object> inputRefs,
            Map<String, Object> parameters) {

        Map<String, Object> request = new HashMap<>();
        request.put("task_id", taskId);
        request.put("project_id", projectId);
        request.put("task_type", taskType);
        request.put("input_refs", inputRefs != null ? inputRefs : new HashMap<>());
        request.put("parameters", parameters != null ? parameters : new HashMap<>());

        return request;
    }
}
