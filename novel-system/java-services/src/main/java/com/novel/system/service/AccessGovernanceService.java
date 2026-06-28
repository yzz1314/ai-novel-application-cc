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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AccessGovernanceService {

    private static final String ACTIVE = "ACTIVE";
    private static final String REVOKED = "REVOKED";
    private static final Set<String> GOVERNANCE_ROLES = Set.of("owner", "admin");

    private final AccessOrganizationRepository organizationRepository;
    private final AccessUserRepository userRepository;
    private final AccessOrganizationMemberRepository organizationMemberRepository;
    private final AccessAuditEventRepository auditEventRepository;

    @Transactional
    public void ensureOrganizationMembership(RequestAccessContext context) {
        if (context == null || !context.authenticated()) {
            return;
        }
        String organizationId = normalizeOrganizationId(context.organizationId());
        String userId = normalizeUserId(context.userId());
        String id = organizationMemberId(organizationId, userId);
        AccessOrganizationMember existing = organizationMemberRepository.findById(id).orElse(null);
        if (existing != null) {
            return;
        }
        AccessOrganizationMember member = new AccessOrganizationMember();
        member.setId(id);
        member.setOrganizationId(organizationId);
        member.setUserId(userId);
        member.setRole(RequestAccessContext.normalizeRole(context.primaryRole()));
        member.setStatus(ACTIVE);
        member.setGrantedBy("identity");
        organizationMemberRepository.save(member);
    }

    public List<Map<String, Object>> listOrganizations() {
        return organizationRepository.findAll()
            .stream()
            .sorted(Comparator.comparing(AccessOrganization::getId))
            .map(this::organizationResponse)
            .toList();
    }

    public List<Map<String, Object>> listOrganizationMembers(String organizationId) {
        return organizationMemberRepository
            .findByOrganizationIdAndStatusOrderByCreatedAtAsc(normalizeOrganizationId(organizationId), ACTIVE)
            .stream()
            .map(this::organizationMemberResponse)
            .toList();
    }

    public List<Map<String, Object>> listUserOrganizationMemberships(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return organizationMemberRepository
            .findByUserIdAndStatusOrderByUpdatedAtDesc(normalizeUserId(userId), ACTIVE)
            .stream()
            .map(this::organizationMemberResponse)
            .toList();
    }

    @Transactional
    public AccessOrganizationMember grantOrganizationMember(
            RequestAccessContext context,
            String organizationId,
            String userId,
            String displayName,
            String role) {
        assertGovernanceAllowed(context, "grant organization member");
        String normalizedOrganizationId = normalizeOrganizationId(organizationId);
        String normalizedUserId = normalizeUserId(userId);
        upsertOrganization(normalizedOrganizationId, normalizedOrganizationId);
        upsertUser(normalizedUserId, displayName, normalizedOrganizationId);
        AccessOrganizationMember member = organizationMemberRepository
            .findById(organizationMemberId(normalizedOrganizationId, normalizedUserId))
            .orElseGet(AccessOrganizationMember::new);
        member.setId(organizationMemberId(normalizedOrganizationId, normalizedUserId));
        member.setOrganizationId(normalizedOrganizationId);
        member.setUserId(normalizedUserId);
        member.setRole(RequestAccessContext.normalizeRole(role));
        member.setStatus(ACTIVE);
        member.setGrantedBy(context.actor());
        AccessOrganizationMember saved = organizationMemberRepository.save(member);
        recordAudit(
            "organization_member_granted",
            context,
            normalizedOrganizationId,
            null,
            normalizedUserId,
            normalizedOrganizationId,
            "grant_organization_member",
            "success",
            "role=" + saved.getRole()
        );
        return saved;
    }

    @Transactional
    public AccessOrganizationMember revokeOrganizationMember(
            RequestAccessContext context,
            String organizationId,
            String userId) {
        assertGovernanceAllowed(context, "revoke organization member");
        String normalizedOrganizationId = normalizeOrganizationId(organizationId);
        String normalizedUserId = normalizeUserId(userId);
        AccessOrganizationMember member = organizationMemberRepository
            .findByOrganizationIdAndUserIdAndStatus(normalizedOrganizationId, normalizedUserId, ACTIVE)
            .orElseThrow(() -> new IllegalArgumentException("Organization member does not exist: " + normalizedUserId));
        member.setStatus(REVOKED);
        member.setGrantedBy(context.actor());
        AccessOrganizationMember saved = organizationMemberRepository.save(member);
        recordAudit(
            "organization_member_revoked",
            context,
            normalizedOrganizationId,
            null,
            normalizedUserId,
            normalizedOrganizationId,
            "revoke_organization_member",
            "success",
            null
        );
        return saved;
    }

    @Transactional
    public AccessAuditEvent recordAudit(
            String eventType,
            RequestAccessContext context,
            String organizationId,
            String projectId,
            String targetUserId,
            String targetOrganizationId,
            String action,
            String outcome,
            String reason) {
        AccessAuditEvent event = new AccessAuditEvent();
        event.setEventType(nonBlank(eventType, "access_event"));
        event.setActorId(context == null ? null : blankToNull(context.userId()));
        event.setActorName(context == null ? null : blankToNull(context.actor()));
        event.setOrganizationId(blankToNull(organizationId));
        event.setProjectId(blankToNull(projectId));
        event.setTargetUserId(blankToNull(targetUserId));
        event.setTargetOrganizationId(blankToNull(targetOrganizationId));
        event.setAction(blankToNull(action));
        event.setOutcome(nonBlank(outcome, "success"));
        event.setReason(truncate(blankToNull(reason), 1024));
        return auditEventRepository.save(event);
    }

    public Map<String, Object> listAuditEvents(String organizationId, String projectId, String actorId, Integer limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit == null ? 100 : limit, 500)));
        List<AccessAuditEvent> events;
        if (projectId != null && !projectId.isBlank()) {
            events = auditEventRepository.findByProjectIdOrderByCreatedAtDesc(projectId.trim(), page);
        } else if (organizationId != null && !organizationId.isBlank()) {
            events = auditEventRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId.trim(), page);
        } else if (actorId != null && !actorId.isBlank()) {
            events = auditEventRepository.findByActorIdOrderByCreatedAtDesc(actorId.trim(), page);
        } else {
            events = auditEventRepository.findAllByOrderByCreatedAtDesc(page);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", events.stream().map(this::auditResponse).toList());
        response.put("limit", page.getPageSize());
        return response;
    }

    public Map<String, Object> organizationMemberResponse(AccessOrganizationMember member) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", member.getId());
        response.put("organizationId", member.getOrganizationId());
        response.put("userId", member.getUserId());
        response.put("role", member.getRole());
        response.put("status", member.getStatus());
        response.put("grantedBy", member.getGrantedBy());
        response.put("createdAt", member.getCreatedAt());
        response.put("updatedAt", member.getUpdatedAt());
        userRepository.findById(member.getUserId()).ifPresent(user -> {
            response.put("displayName", user.getDisplayName());
            response.put("lastSeenAt", user.getLastSeenAt());
        });
        return response;
    }

    public Map<String, Object> auditResponse(AccessAuditEvent event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", event.getId());
        response.put("eventType", event.getEventType());
        response.put("actorId", event.getActorId());
        response.put("actorName", event.getActorName());
        response.put("organizationId", event.getOrganizationId());
        response.put("projectId", event.getProjectId());
        response.put("targetUserId", event.getTargetUserId());
        response.put("targetOrganizationId", event.getTargetOrganizationId());
        response.put("action", event.getAction());
        response.put("outcome", event.getOutcome());
        response.put("reason", event.getReason());
        response.put("createdAt", event.getCreatedAt());
        return response;
    }

    private Map<String, Object> organizationResponse(AccessOrganization organization) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", organization.getId());
        response.put("name", organization.getName());
        response.put("status", organization.getStatus());
        response.put("createdAt", organization.getCreatedAt());
        response.put("updatedAt", organization.getUpdatedAt());
        return response;
    }

    private void assertGovernanceAllowed(RequestAccessContext context, String action) {
        if (context == null || !context.hasAnyRole(GOVERNANCE_ROLES)) {
            String role = context == null ? "anonymous" : context.primaryRole();
            throw new AccessDeniedException("Role " + role + " cannot perform access governance action " + action);
        }
    }

    private void upsertOrganization(String organizationId, String name) {
        AccessOrganization organization = organizationRepository.findById(organizationId).orElseGet(AccessOrganization::new);
        organization.setId(organizationId);
        organization.setName(nonBlank(name, organizationId));
        organization.setStatus(ACTIVE);
        organizationRepository.save(organization);
    }

    private void upsertUser(String userId, String displayName, String organizationId) {
        AccessUser user = userRepository.findById(userId).orElseGet(AccessUser::new);
        user.setId(userId);
        user.setDisplayName(blankToNull(displayName));
        user.setOrganizationId(organizationId);
        user.setStatus(ACTIVE);
        userRepository.save(user);
    }

    private String normalizeOrganizationId(String organizationId) {
        return nonBlank(organizationId, "local");
    }

    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return userId.trim();
    }

    private String organizationMemberId(String organizationId, String userId) {
        return normalizeOrganizationId(organizationId) + "_" + normalizeUserId(userId);
    }

    private String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
