package com.novel.system.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonClientServiceTest {

    @Test
    void fallsBackToLegacyRunEndpointWhenAgentSpecificEndpointIsMissing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PythonClientService service = new PythonClientService(restTemplate);
        ReflectionTestUtils.setField(service, "pythonServiceUrl", "http://python.local");

        Map<String, Object> request = Map.of(
            "task_id", "task-1",
            "project_id", "project-1",
            "task_type", "sample_import",
            "input_refs", Map.of("sample_id", "sample-1"),
            "parameters", Map.of("sample_id", "sample-1")
        );

        server.expect(requestTo("http://python.local/api/agents/sample_import/run"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"Not Found\"}"));
        server.expect(requestTo("http://python.local/api/agents/run"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andRespond(withSuccess(
                "{\"task_id\":\"task-1\",\"status\":\"success\",\"structured_output\":{\"title\":\"ok\"}}",
                MediaType.APPLICATION_JSON
            ));

        Map<String, Object> response = service.callAgent("sample_import", request);

        assertThat(response).containsEntry("status", "success");
        server.verify();
    }
}
