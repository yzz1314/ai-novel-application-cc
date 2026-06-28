package com.novel.system.service;

import com.novel.system.entity.ProjectMember;
import com.novel.system.repository.ProjectMemberRepository;
import com.novel.system.security.RequestAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectAccessServiceTest {

    private ProjectMemberRepository repository;
    private ProjectAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectMemberRepository.class);
        service = new ProjectAccessService(repository);
    }

    @Test
    void grantProjectAccessNormalizesRoleAndStoresMember() {
        when(repository.findById("project-a_user-1")).thenReturn(Optional.empty());
        when(repository.save(any(ProjectMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectMember member = service.grantProjectAccess(
            "project-a",
            " user-1 ",
            "Alice",
            "org-1",
            "manager",
            "Owner"
        );

        assertThat(member.getId()).isEqualTo("project-a_user-1");
        assertThat(member.getUserId()).isEqualTo("user-1");
        assertThat(member.getRole()).isEqualTo("artifact_manager");
        assertThat(member.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void resolveProjectAccessUsesActiveDbMembership() {
        ProjectMember member = new ProjectMember();
        member.setProjectId("project-a");
        member.setUserId("user-1");
        member.setRole("editor");
        member.setStatus("ACTIVE");
        when(repository.findByProjectIdAndUserIdAndStatus("project-a", "user-1", "ACTIVE"))
            .thenReturn(Optional.of(member));

        RequestAccessContext context = new RequestAccessContext(
            "user-1",
            "Alice",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-b"),
            true
        );

        RequestAccessContext resolved = service.resolveProjectAccess(context, "project-a");

        assertThat(resolved.hasProjectAccess("project-a")).isTrue();
        assertThat(resolved.roles()).contains("viewer", "editor");
    }
}
