package com.novel.system.service;

import com.novel.system.entity.AccessRolePolicy;
import com.novel.system.exception.AccessDeniedException;
import com.novel.system.repository.AccessRolePolicyRepository;
import com.novel.system.security.RequestAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessRolePolicyServiceTest {

    private AccessRolePolicyRepository repository;
    private AccessRolePolicyService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccessRolePolicyRepository.class);
        service = new AccessRolePolicyService(repository);
    }

    @Test
    void usesDefaultProjectMutationPolicyWhenNoSavedPolicyExists() {
        when(repository.findByActionKeyAndStatus(AccessRolePolicyService.ACTION_PROJECT_MUTATION, "ACTIVE"))
            .thenReturn(Optional.empty());

        assertThat(service.isAllowed(context("editor"), AccessRolePolicyService.ACTION_PROJECT_MUTATION)).isTrue();
        assertThat(service.isAllowed(context("viewer"), AccessRolePolicyService.ACTION_PROJECT_MUTATION)).isFalse();
    }

    @Test
    void savedPolicyOverridesDefaultRoles() {
        AccessRolePolicy policy = new AccessRolePolicy();
        policy.setActionKey(AccessRolePolicyService.ACTION_PROJECT_MUTATION);
        policy.setAllowedRoles("viewer");
        policy.setStatus("ACTIVE");
        when(repository.findByActionKeyAndStatus(AccessRolePolicyService.ACTION_PROJECT_MUTATION, "ACTIVE"))
            .thenReturn(Optional.of(policy));

        assertThat(service.isAllowed(context("viewer"), AccessRolePolicyService.ACTION_PROJECT_MUTATION)).isTrue();
        assertThat(service.isAllowed(context("editor"), AccessRolePolicyService.ACTION_PROJECT_MUTATION)).isFalse();
    }

    @Test
    void updatePolicyRequiresGovernancePermissionAndNormalizesRoles() {
        when(repository.findByActionKeyAndStatus(AccessRolePolicyService.ACTION_ACCESS_GOVERNANCE, "ACTIVE"))
            .thenReturn(Optional.empty());
        when(repository.findById("custom_action")).thenReturn(Optional.empty());
        when(repository.save(any(AccessRolePolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccessRolePolicy policy = service.updatePolicy(
            context("admin"),
            "CUSTOM_ACTION",
            List.of("Reader", "manager"),
            "custom"
        );

        assertThat(policy.getActionKey()).isEqualTo("custom_action");
        assertThat(policy.getAllowedRoles()).isEqualTo("viewer,artifact_manager");
        assertThat(policy.getUpdatedBy()).isEqualTo("Alice");
    }

    @Test
    void updatePolicyRejectsNonGovernanceRole() {
        assertThatThrownBy(() -> service.updatePolicy(
            context("viewer"),
            "custom_action",
            List.of("viewer"),
            "custom"
        ))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot perform action update role policy");
    }

    private RequestAccessContext context(String role) {
        return new RequestAccessContext(
            "u-1",
            "Alice",
            "org-1",
            RequestAccessContext.normalizeRoles(role),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );
    }
}
