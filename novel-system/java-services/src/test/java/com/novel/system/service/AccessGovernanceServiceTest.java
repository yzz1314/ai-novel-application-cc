package com.novel.system.service;

import com.novel.system.entity.AccessAuditEvent;
import com.novel.system.entity.AccessOrganization;
import com.novel.system.entity.AccessOrganizationMember;
import com.novel.system.entity.AccessUser;
import com.novel.system.exception.AccessDeniedException;
import com.novel.system.repository.AccessAuditEventRepository;
import com.novel.system.repository.AccessOrganizationMemberRepository;
import com.novel.system.repository.AccessOrganizationRepository;
import com.novel.system.repository.AccessUserRepository;
import com.novel.system.security.RequestAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessGovernanceServiceTest {

    private AccessOrganizationRepository organizationRepository;
    private AccessUserRepository userRepository;
    private AccessOrganizationMemberRepository memberRepository;
    private AccessAuditEventRepository auditEventRepository;
    private AccessGovernanceService service;

    @BeforeEach
    void setUp() {
        organizationRepository = mock(AccessOrganizationRepository.class);
        userRepository = mock(AccessUserRepository.class);
        memberRepository = mock(AccessOrganizationMemberRepository.class);
        auditEventRepository = mock(AccessAuditEventRepository.class);
        service = new AccessGovernanceService(
            organizationRepository,
            userRepository,
            memberRepository,
            auditEventRepository
        );
    }

    @Test
    void grantOrganizationMemberRequiresGovernanceRoleAndWritesAudit() {
        when(organizationRepository.findById("org-1")).thenReturn(Optional.empty());
        when(userRepository.findById("user-2")).thenReturn(Optional.empty());
        when(memberRepository.findById("org-1_user-2")).thenReturn(Optional.empty());
        when(memberRepository.save(any(AccessOrganizationMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditEventRepository.save(any(AccessAuditEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccessOrganizationMember member = service.grantOrganizationMember(
            context("admin"),
            "org-1",
            " user-2 ",
            "Bob",
            "manager"
        );

        assertThat(member.getId()).isEqualTo("org-1_user-2");
        assertThat(member.getRole()).isEqualTo("artifact_manager");
        verify(organizationRepository).save(any(AccessOrganization.class));
        verify(userRepository).save(any(AccessUser.class));
        verify(auditEventRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
            "organization_member_granted".equals(event.getEventType())
                && "u-1".equals(event.getActorId())
                && "user-2".equals(event.getTargetUserId())
                && "success".equals(event.getOutcome())
        ));
    }

    @Test
    void viewerCannotGrantOrganizationMember() {
        assertThatThrownBy(() -> service.grantOrganizationMember(
            context("viewer"),
            "org-1",
            "user-2",
            "Bob",
            "viewer"
        ))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot perform access governance action");
    }

    @Test
    void listAuditEventsCanFilterByOrganization() {
        AccessAuditEvent event = new AccessAuditEvent();
        event.setId("audit-1");
        event.setEventType("project_member_granted");
        event.setOrganizationId("org-1");
        event.setOutcome("success");
        when(auditEventRepository.findByOrganizationIdOrderByCreatedAtDesc(
            org.mockito.ArgumentMatchers.eq("org-1"),
            any(Pageable.class)
        )).thenReturn(List.of(event));

        var response = service.listAuditEvents("org-1", null, null, 50);

        assertThat(response.get("items")).asList().hasSize(1);
        assertThat(response).containsEntry("limit", 50);
    }

    @Test
    void ensureOrganizationMembershipDoesNotReactivateRevokedMember() {
        AccessOrganizationMember member = new AccessOrganizationMember();
        member.setId("org-1_u-1");
        member.setOrganizationId("org-1");
        member.setUserId("u-1");
        member.setRole("viewer");
        member.setStatus("REVOKED");
        when(memberRepository.findById("org-1_u-1")).thenReturn(Optional.of(member));

        service.ensureOrganizationMembership(context("admin"));

        verify(memberRepository, never()).save(any(AccessOrganizationMember.class));
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
