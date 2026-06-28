package com.novel.system.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record RequestAccessContext(
    String userId,
    String actor,
    String organizationId,
    Set<String> roles,
    Set<String> projectIds,
    boolean authenticated
) {

    public static RequestAccessContext local() {
        return new RequestAccessContext(
            "local-user",
            "local-user",
            "local",
            Set.of("owner"),
            Set.of("*"),
            false
        );
    }

    public String primaryRole() {
        return roles.stream().findFirst().orElse("viewer");
    }

    public boolean hasProjectAccess(String projectId) {
        return projectIds.contains("*") || projectIds.contains(projectId);
    }

    public boolean hasAnyRole(Set<String> allowedRoles) {
        return roles.stream().anyMatch(allowedRoles::contains);
    }

    public RequestAccessContext withProjectAccess(String projectId, String role) {
        Set<String> newProjects = new LinkedHashSet<>(projectIds);
        if (projectId != null && !projectId.isBlank()) {
            newProjects.add(projectId);
        }
        Set<String> newRoles = new LinkedHashSet<>(roles);
        newRoles.add(normalizeRole(role));
        return new RequestAccessContext(
            userId,
            actor,
            organizationId,
            Collections.unmodifiableSet(newRoles),
            Collections.unmodifiableSet(newProjects),
            authenticated
        );
    }

    public static String normalizeRole(String value) {
        String role = value == null || value.isBlank() ? "viewer" : value.trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "manager" -> "artifact_manager";
            case "reader" -> "viewer";
            default -> role;
        };
    }

    public static Set<String> normalizeRoles(String rawRoles) {
        if (rawRoles == null || rawRoles.isBlank()) {
            return Set.of("viewer");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String item : rawRoles.split(",")) {
            String role = normalizeRole(item);
            if (!role.isBlank()) {
                roles.add(role);
            }
        }
        return roles.isEmpty() ? Set.of("viewer") : Collections.unmodifiableSet(roles);
    }

    public static Set<String> normalizeProjectIds(String rawProjectIds) {
        if (rawProjectIds == null || rawProjectIds.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String item : rawProjectIds.split(",")) {
            String id = item.trim();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? Set.of() : Collections.unmodifiableSet(ids);
    }
}
