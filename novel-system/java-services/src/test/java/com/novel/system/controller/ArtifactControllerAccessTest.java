package com.novel.system.controller;

import com.novel.system.exception.GlobalExceptionHandler;
import com.novel.system.security.AccessControlService;
import com.novel.system.security.AccessControlInterceptor;
import com.novel.system.security.RequestAccessContext;
import com.novel.system.service.AccessIdentityService;
import com.novel.system.service.ProjectAccessService;
import com.novel.system.service.ArtifactService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArtifactControllerAccessTest {

    @Test
    void queryArtifactUsesAuthenticatedHeaderActorAndRole() throws Exception {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifact(eq("project-a"), eq("analysis/report.md"), eq(false), eq("Alice"), eq("inspect"), eq("auditor")))
            .thenReturn(Map.of("status", "ok"));
        MockMvc mockMvc = mockMvc(artifactService, false, false);

        mockMvc.perform(get("/api/projects/project-a/artifacts/view")
                .param("path", "analysis/report.md")
                .param("actor", "Mallory")
                .param("role", "viewer")
                .param("reason", "inspect")
                .header("X-User-Id", "u-1")
                .header("X-Actor", "Alice")
                .header("X-Role", "auditor")
                .header("X-Project-Ids", "project-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void bodyRoleCannotEscalateWhenHeaderRoleIsViewer() throws Exception {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.archiveArtifact(eq("project-a"), any())).thenReturn(Map.of("status", "archived"));
        MockMvc mockMvc = mockMvc(artifactService, false, false);

        mockMvc.perform(post("/api/projects/project-a/artifacts/archive")
                .contentType("application/json")
                .content("{\"path\":\"analysis/report.md\",\"actor\":\"Mallory\",\"role\":\"owner\"}")
                .header("X-Actor", "Reader")
                .header("X-Role", "viewer")
                .header("X-Project-Ids", "project-a"))
            .andExpect(status().isOk());

        verify(artifactService).archiveArtifact(eq("project-a"), org.mockito.ArgumentMatchers.argThat(request ->
            "Reader".equals(request.get("actor")) && "viewer".equals(request.get("role"))
        ));
    }

    @Test
    void enforcedAccessRejectsProjectNotInHeader() throws Exception {
        ArtifactService artifactService = mock(ArtifactService.class);
        MockMvc mockMvc = mockMvc(artifactService, true, true);

        mockMvc.perform(get("/api/projects/project-b/artifacts")
                .header("X-Actor", "Alice")
                .header("X-Role", "owner")
                .header("X-Project-Ids", "project-a"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    private MockMvc mockMvc(ArtifactService artifactService, boolean enforceAccess, boolean requireAuthentication) {
        ArtifactController controller = new ArtifactController(artifactService);
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        when(projectAccessService.resolveProjectAccess(any(RequestAccessContext.class), any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AccessControlService accessControlService = new AccessControlService(projectAccessService);
        ReflectionTestUtils.setField(accessControlService, "enforceAccess", enforceAccess);
        ReflectionTestUtils.setField(accessControlService, "requireAuthentication", requireAuthentication);
        AccessIdentityService accessIdentityService = mock(AccessIdentityService.class);
        return standaloneSetup(controller)
            .addInterceptors(new AccessControlInterceptor(accessControlService, accessIdentityService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }
}
