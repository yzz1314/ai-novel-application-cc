package com.novel.system.service;

import com.novel.system.security.RequestAccessContext;
import com.novel.system.security.RequestAccessContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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
        String normalizedAgentName = normalizeAgentName(agentName);
        String agentSpecificUrl = pythonServiceUrl + "/api/agents/" + normalizedAgentName + "/run";
        String legacyRunUrl = pythonServiceUrl + "/api/agents/run";

        log.info("Calling Python agent: {} at {}", normalizedAgentName, agentSpecificUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        propagateAccessHeaders(headers, request);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        try {
            return postAgentRequest(agentSpecificUrl, entity, normalizedAgentName);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn(
                "Agent-specific Python endpoint missing for {}; falling back to legacy endpoint: {}",
                normalizedAgentName,
                legacyRunUrl
            );
            return postAgentRequest(legacyRunUrl, entity, normalizedAgentName);
        } catch (Exception e) {
            log.error("Failed to call agent: {}", normalizedAgentName, e);
            throw new RuntimeException("调用Python服务失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> postAgentRequest(
            String url,
            HttpEntity<Map<String, Object>> entity,
            String normalizedAgentName) {
        ResponseEntity<Map> response = restTemplate.exchange(
            url,
            HttpMethod.POST,
            entity,
            Map.class
        );

        if (response.getStatusCode() == HttpStatus.OK) {
            log.info("Agent call successful: {}", normalizedAgentName);
            return response.getBody();
        }
        throw new RuntimeException("Agent调用失败，状态码: " + response.getStatusCode());
    }

    /**
     * 取消任务
     */
    public void cancelTask(String taskId) {
        String url = pythonServiceUrl + "/api/agents/tasks/" + taskId + "/cancel";

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
        request.put("config", parameters != null ? parameters : new HashMap<>());
        if (parameters != null && parameters.get("model_profile_id") != null) {
            request.put("model_profile_id", parameters.get("model_profile_id"));
        }
        if (parameters != null && parameters.get("resume_from_checkpoint") != null) {
            request.put("resume_from_checkpoint", parameters.get("resume_from_checkpoint"));
        }

        return request;
    }

    private void propagateAccessHeaders(HttpHeaders headers, Map<String, Object> request) {
        RequestAccessContext context = RequestAccessContextHolder.current();
        headers.set("X-User-Id", context.userId());
        headers.set("X-Actor", context.actor());
        headers.set("X-Org-Id", context.organizationId());
        headers.set("X-Roles", String.join(",", context.roles()));
        Object projectId = request == null ? null : request.get("project_id");
        headers.set(
            "X-Project-Id",
            projectId == null || String.valueOf(projectId).isBlank()
                ? String.join(",", context.projectIds())
                : String.valueOf(projectId)
        );
    }

    private String normalizeAgentName(String agentName) {
        if (agentName == null) {
            return "";
        }

        return switch (agentName) {
            case "SampleImportAgent" -> "sample_import";
            case "FullTextAnalysisAgent" -> "full_text_analysis";
            case "BookSummaryAgent" -> "book_summary";
            case "CrossBookSynthesisAgent" -> "cross_book_synthesis";
            case "SkillGeneratorAgent" -> "skill_generation";
            case "OutlineGeneratorAgent" -> "outline_generation";
            case "ChapterWriterAgent" -> "chapter_writing";
            case "RevisionAgent" -> "chapter_revision";
            case "MemoryExtractorAgent" -> "memory_extraction";
            case "MemoryQueryAgent" -> "memory_query";
            case "GraphBuilderAgent" -> "graph_build";
            default -> agentName;
        };
    }
}
