package com.novel.system.service;

import com.novel.system.entity.AccessOrganization;
import com.novel.system.entity.AccessUser;
import com.novel.system.repository.AccessOrganizationRepository;
import com.novel.system.repository.AccessUserRepository;
import com.novel.system.security.RequestAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessIdentityServiceTest {

    private AccessUserRepository userRepository;
    private AccessOrganizationRepository organizationRepository;
    private ProjectAccessService projectAccessService;
    private AccessIdentityService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(AccessUserRepository.class);
        organizationRepository = mock(AccessOrganizationRepository.class);
        projectAccessService = mock(ProjectAccessService.class);
        service = new AccessIdentityService(userRepository, organizationRepository, projectAccessService);
    }

    @Test
    void recordIdentityUpsertsOrganizationAndUser() {
        when(organizationRepository.findById("org-1")).thenReturn(Optional.empty());
        when(userRepository.findById("user-1")).thenReturn(Optional.empty());

        service.recordIdentity(context());

        verify(organizationRepository).save(orgWith("org-1"));
        verify(userRepository).save(userWith("user-1", "Alice", "org-1"));
    }

    @Test
    void currentIdentityReturnsPersistedUserOrganizationAndMemberships() {
        AccessUser user = new AccessUser();
        user.setId("user-1");
        user.setDisplayName("Alice");
        user.setOrganizationId("org-1");
        user.setStatus("ACTIVE");
        AccessOrganization organization = new AccessOrganization();
        organization.setId("org-1");
        organization.setName("org-1");
        organization.setStatus("ACTIVE");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(organization));
        when(projectAccessService.listUserMemberships("user-1")).thenReturn(List.of(Map.of(
            "projectId", "project-a",
            "role", "editor"
        )));

        Map<String, Object> response = service.currentIdentity(context());

        assertThat(response)
            .containsEntry("authenticated", true)
            .containsEntry("userId", "user-1")
            .containsEntry("organizationId", "org-1");
        assertThat(response.get("projectMemberships")).asList().hasSize(1);
    }

    private RequestAccessContext context() {
        return new RequestAccessContext(
            "user-1",
            "Alice",
            "org-1",
            RequestAccessContext.normalizeRoles("editor"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );
    }

    private AccessOrganization orgWith(String id) {
        return org.mockito.ArgumentMatchers.argThat(org ->
            id.equals(org.getId()) && "ACTIVE".equals(org.getStatus())
        );
    }

    private AccessUser userWith(String id, String displayName, String organizationId) {
        return org.mockito.ArgumentMatchers.argThat(user ->
            id.equals(user.getId())
                && displayName.equals(user.getDisplayName())
                && organizationId.equals(user.getOrganizationId())
                && "ACTIVE".equals(user.getStatus())
                && user.getLastSeenAt() != null
        );
    }
}
