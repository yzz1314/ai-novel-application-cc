package com.novel.system.controller;

import com.novel.system.service.TaskExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TaskControllerTest {

    @Test
    void streamTaskLogsEmitsSnapshotAndCompletionForTerminalTask() throws Exception {
        TaskExecutorService taskExecutorService = mock(TaskExecutorService.class);
        when(taskExecutorService.getTaskLogs(eq("task_stream"), eq(12))).thenReturn(Map.of(
            "taskId", "task_stream",
            "projectId", "project_stream",
            "taskType", "sample_import",
            "status", "SUCCESS",
            "events", List.of(Map.of("eventType", "finished")),
            "eventLogTail", Map.of("lineCount", 1)
        ));
        TaskController controller = new TaskController(taskExecutorService);
        MockMvc mockMvc = standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(get("/api/tasks/task_stream/logs/stream")
                .param("tailLines", "12"))
            .andExpect(request().asyncStarted())
            .andReturn();
        result.getAsyncResult(3000L);

        MvcResult completed = mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andReturn();

        String body = completed.getResponse().getContentAsString();
        assertThat(body)
            .contains("event:snapshot")
            .contains("event:complete")
            .contains("task_stream")
            .contains("SUCCESS");
    }

    private static org.springframework.test.web.servlet.RequestBuilder asyncDispatch(MvcResult result) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result);
    }
}
