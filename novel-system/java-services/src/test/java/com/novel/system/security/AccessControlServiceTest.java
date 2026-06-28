package com.novel.system.security;

import com.novel.system.exception.AccessDeniedException;
import com.novel.system.service.AccessRolePolicyService;
import com.novel.system.service.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class AccessControlServiceTest {

    @Test
    void buildsRequestContextFromHeaders() {
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService());
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
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService());
        ReflectionTestUtils.setField(service, "requireAuthentication", true);

        assertThatThrownBy(() -> service.fromRequest(new MockHttpServletRequest()))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Authentication headers are required");
    }

    @Test
    void rejectsUnauthorizedProjectWhenEnforced() {
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService());
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
        AccessRolePolicyService policyService = mockPolicyService();
        AccessControlService service = service(mock(ProjectAccessService.class), policyService);
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

    @Test
    void resolvesProjectAccessThroughMembershipService() {
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        AccessControlService service = service(projectAccessService, mockPolicyService());
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Alice",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );
        RequestAccessContext resolved = context.withProjectAccess("project-b", "editor");
        when(projectAccessService.resolveProjectAccess(context, "project-b")).thenReturn(resolved);

        assertThat(service.resolveProjectAccess(context, "project-b")).isSameAs(resolved);
    }

    @Test
    void mutationPolicyCanAllowViewerWhenConfigured() {
        AccessRolePolicyService policyService = mock(AccessRolePolicyService.class);
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Reader",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );
        AccessControlService service = service(mock(ProjectAccessService.class), policyService);
        ReflectionTestUtils.setField(service, "enforceAccess", true);

        service.assertMutationAllowed(context, "project-a", "patch");

        org.mockito.Mockito.verify(policyService).assertAllowed(
            context,
            AccessRolePolicyService.ACTION_PROJECT_MUTATION,
            "patch"
        );
    }

    private AccessControlService service(ProjectAccessService projectAccessService, AccessRolePolicyService policyService) {
        return new AccessControlService(projectAccessService, policyService);
    }

    private AccessRolePolicyService mockPolicyService() {
        AccessRolePolicyService policyService = mock(AccessRolePolicyService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            RequestAccessContext context = invocation.getArgument(0);
            String action = invocation.getArgument(2);
            if (context == null || !context.hasAnyRole(java.util.Set.of("owner", "admin", "editor", "artifact_manager"))) {
                throw new AccessDeniedException("Role " + (context == null ? "anonymous" : context.primaryRole()) + " cannot perform action " + action);
            }
            return null;
        }).when(policyService).assertAllowed(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(AccessRolePolicyService.ACTION_PROJECT_MUTATION),
            org.mockito.ArgumentMatchers.anyString()
        );
        return policyService;
    }
}
