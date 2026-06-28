package com.novel.system.service;

import com.novel.system.entity.ProjectMember;
import com.novel.system.repository.ProjectMemberRepository;
import com.novel.system.security.RequestAccessContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private static final String ACTIVE = "ACTIVE";
    private static final String REVOKED = "REVOKED";

    private final ProjectMemberRepository projectMemberRepository;

    @Transactional
    public ProjectMember grantProjectAccess(
            String projectId,
            String userId,
            String actor,
            String organizationId,
            String role,
            String grantedBy) {
        String normalizedUser = normalizeUserId(userId);
        ProjectMember member = projectMemberRepository
            .findById(memberId(projectId, normalizedUser))
            .orElseGet(ProjectMember::new);
        member.setId(memberId(projectId, normalizedUser));
        member.setProjectId(projectId);
        member.setUserId(normalizedUser);
        member.setActor(blankToNull(actor));
        member.setOrganizationId(blankToNull(organizationId));
        member.setRole(RequestAccessContext.normalizeRole(role));
        member.setGrantedBy(blankToNull(grantedBy));
        member.setStatus(ACTIVE);
        return projectMemberRepository.save(member);
    }

    @Transactional
    public ProjectMember revokeProjectAccess(String projectId, String userId, String revokedBy) {
        ProjectMember member = projectMemberRepository
            .findByProjectIdAndUserIdAndStatus(projectId, normalizeUserId(userId), ACTIVE)
            .orElseThrow(() -> new IllegalArgumentException("Project member does not exist: " + userId));
        member.setStatus(REVOKED);
        member.setGrantedBy(blankToNull(revokedBy));
        return projectMemberRepository.save(member);
    }

    public List<Map<String, Object>> listProjectMembers(String projectId) {
        return projectMemberRepository.findByProjectIdAndStatusOrderByCreatedAtAsc(projectId, ACTIVE)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public List<Map<String, Object>> listUserMemberships(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return projectMemberRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(normalizeUserId(userId), ACTIVE)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public Optional<ProjectMember> findActiveMember(String projectId, String userId) {
        if (projectId == null || projectId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return projectMemberRepository.findByProjectIdAndUserIdAndStatus(projectId, normalizeUserId(userId), ACTIVE);
    }

    public RequestAccessContext resolveProjectAccess(RequestAccessContext context, String projectId) {
        if (context.hasProjectAccess(projectId)) {
            return context;
        }
        return findActiveMember(projectId, context.userId())
            .map(member -> context.withProjectAccess(projectId, member.getRole()))
            .orElse(context);
    }

    public Map<String, Object> toResponse(ProjectMember member) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", member.getId());
        response.put("projectId", member.getProjectId());
        response.put("userId", member.getUserId());
        response.put("actor", member.getActor());
        response.put("organizationId", member.getOrganizationId());
        response.put("role", member.getRole());
        response.put("status", member.getStatus());
        response.put("grantedBy", member.getGrantedBy());
        response.put("createdAt", member.getCreatedAt());
        response.put("updatedAt", member.getUpdatedAt());
        return response;
    }

    public static String memberId(String projectId, String userId) {
        return projectId + "_" + normalizeUserId(userId);
    }

    public static String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return userId.trim();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
