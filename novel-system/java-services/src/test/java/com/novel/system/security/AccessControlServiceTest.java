package com.novel.system.security;

import com.novel.system.exception.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessControlServiceTest {

    @Test
    void buildsRequestContextFromHeaders() {
        AccessControlService service = new AccessControlService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "u-1");
        request.addHeader("X-Actor", "Alice");
        request.addHeader("X-Org-Id", "org-1");
        request.addHeader("X-Roles", "admin,artifact_manager");
        request.addHeader("X-Project-Ids", "project-a,project-b");

        RequestAccessContext context = service.fromRequest(request);

        assertThat(context.authenticated()).isTrue();
        assertThat(context.userId()).isEqualTo("u-1");
        assertThat(context.actor()).isEqualTo("Alice");
        assertThat(context.organizationId()).isEqualTo("org-1");
        assertThat(context.roles()).containsExactlyInAnyOrder("admin", "artifact_manager");
        assertThat(context.hasProjectAccess("project-b")).isTrue();
        assertThat(context.hasProjectAccess("project-c")).isFalse();
    }

    @Test
    void rejectsMissingAuthenticationWhenRequired() {
        AccessControlService service = new AccessControlService();
        ReflectionTestUtils.setField(service, "requireAuthentication", true);

        assertThatThrownBy(() -> service.fromRequest(new MockHttpServletRequest()))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Authentication headers are required");
    }

    @Test
    void rejectsUnauthorizedProjectWhenEnforced() {
        AccessControlService service = new AccessControlService();
        ReflectionTestUtils.setField(service, "enforceAccess", true);
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Alice",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );

        assertThatThrownBy(() -> service.assertProjectAccess(context, "project-b"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot access project project-b");
    }

    @Test
    void rejectsViewerMutationWhenEnforced() {
        AccessControlService service = new AccessControlService();
        ReflectionTestUtils.setField(service, "enforceAccess", true);
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Reader",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );

        assertThatThrownBy(() -> service.assertMutationAllowed(context, "project-a", "delete project"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot perform action");
    }
}
