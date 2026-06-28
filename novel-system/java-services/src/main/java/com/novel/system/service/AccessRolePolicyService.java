package com.novel.system.service;

import com.novel.system.entity.AccessRolePolicy;
import com.novel.system.exception.AccessDeniedException;
import com.novel.system.repository.AccessRolePolicyRepository;
import com.novel.system.security.RequestAccessContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AccessRolePolicyService {

    public static final String ACTION_PROJECT_MUTATION = "project_mutation";
    public static final String ACTION_ACCESS_GOVERNANCE = "access_governance";

    private static final String ACTIVE = "ACTIVE";
    private static final Set<String> PROJECT_MUTATION_DEFAULT = Set.of("owner", "admin", "editor", "artifact_manager");
    private static final Set<String> ACCESS_GOVERNANCE_DEFAULT = Set.of("owner", "admin");

    private final AccessRolePolicyRepository policyRepository;

    public boolean isAllowed(RequestAccessContext context, String actionKey) {
        if (context == null) {
            return false;
        }
        return context.hasAnyRole(allowedRoles(actionKey));
    }

    public void assertAllowed(RequestAccessContext context, String actionKey, String actionLabel) {
        if (!isAllowed(context, actionKey)) {
            String role = context == null ? "anonymous" : context.primaryRole();
            throw new AccessDeniedException("Role " + role + " cannot perform action " + actionLabel);
        }
    }

    public Set<String> allowedRoles(String actionKey) {
        return policyRepository.findByActionKeyAndStatus(normalizeActionKey(actionKey), ACTIVE)
            .map(AccessRolePolicy::getAllowedRoles)
            .map(this::parseRoles)
            .filter(roles -> !roles.isEmpty())
            .orElseGet(() -> defaultRoles(actionKey));
    }

    public List<Map<String, Object>> listPolicies() {
        Map<String, AccessRolePolicy> saved = new LinkedHashMap<>();
        policyRepository.findByStatusOrderByActionKeyAsc(ACTIVE)
            .forEach(policy -> saved.put(policy.getActionKey(), policy));
        defaultPolicyRows().forEach(policy -> saved.putIfAbsent(policy.getActionKey(), policy));
        return saved.values().stream()
            .sorted(Comparator.comparing(AccessRolePolicy::getActionKey))
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public AccessRolePolicy updatePolicy(
            RequestAccessContext context,
            String actionKey,
            List<?> allowedRoles,
            String description) {
        assertAllowed(context, ACTION_ACCESS_GOVERNANCE, "update role policy");
        String normalizedActionKey = normalizeActionKey(actionKey);
        Set<String> roles = normalizeRoleList(allowedRoles);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("allowedRoles is required");
        }
        AccessRolePolicy policy = policyRepository
            .findById(normalizedActionKey)
            .orElseGet(AccessRolePolicy::new);
        policy.setActionKey(normalizedActionKey);
        policy.setDescription(nonBlank(description, defaultDescription(normalizedActionKey)));
        policy.setAllowedRoles(String.join(",", roles));
        policy.setStatus(ACTIVE);
        policy.setUpdatedBy(context == null ? null : context.actor());
        return policyRepository.save(policy);
    }

    public Map<String, Object> toResponse(AccessRolePolicy policy) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("actionKey", policy.getActionKey());
        response.put("description", policy.getDescription());
        response.put("allowedRoles", parseRoles(policy.getAllowedRoles()).stream().toList());
        response.put("status", policy.getStatus());
        response.put("updatedBy", policy.getUpdatedBy());
        response.put("createdAt", policy.getCreatedAt());
        response.put("updatedAt", policy.getUpdatedAt());
        response.put("default", !policyRepository.existsById(policy.getActionKey()));
        return response;
    }

    private List<AccessRolePolicy> defaultPolicyRows() {
        return List.of(
            defaultPolicy(ACTION_ACCESS_GOVERNANCE, defaultDescription(ACTION_ACCESS_GOVERNANCE), ACCESS_GOVERNANCE_DEFAULT),
            defaultPolicy(ACTION_PROJECT_MUTATION, defaultDescription(ACTION_PROJECT_MUTATION), PROJECT_MUTATION_DEFAULT)
        );
    }

    private AccessRolePolicy defaultPolicy(String actionKey, String description, Set<String> roles) {
        AccessRolePolicy policy = new AccessRolePolicy();
        policy.setActionKey(actionKey);
        policy.setDescription(description);
        policy.setAllowedRoles(String.join(",", roles));
        policy.setStatus(ACTIVE);
        return policy;
    }

    private Set<String> defaultRoles(String actionKey) {
        return switch (normalizeActionKey(actionKey)) {
            case ACTION_ACCESS_GOVERNANCE -> ACCESS_GOVERNANCE_DEFAULT;
            case ACTION_PROJECT_MUTATION -> PROJECT_MUTATION_DEFAULT;
            default -> Set.of("owner", "admin");
        };
    }

    private String defaultDescription(String actionKey) {
        return switch (normalizeActionKey(actionKey)) {
            case ACTION_ACCESS_GOVERNANCE -> "Manage users, organization members, project members, audit events, and role policies.";
            case ACTION_PROJECT_MUTATION -> "Create, update, delete, archive, or mutate project-scoped resources.";
            default -> "Custom access action.";
        };
    }

    private Set<String> normalizeRoleList(List<?> roles) {
        if (roles == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        roles.forEach(role -> {
            if (role != null && !String.valueOf(role).isBlank()) {
                normalized.add(RequestAccessContext.normalizeRole(String.valueOf(role)));
            }
        });
        return normalized;
    }

    private Set<String> parseRoles(String rawRoles) {
        if (rawRoles == null || rawRoles.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String item : rawRoles.split(",")) {
            if (!item.isBlank()) {
                roles.add(RequestAccessContext.normalizeRole(item));
            }
        }
        return roles;
    }

    private String normalizeActionKey(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            throw new IllegalArgumentException("actionKey is required");
        }
        return actionKey.trim().toLowerCase();
    }

    private String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
