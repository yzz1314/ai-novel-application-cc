package com.novel.system.security;

import com.novel.system.exception.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AccessControlService {

    private static final Set<String> DEFAULT_MUTATION_ROLES = Set.of("owner", "admin", "editor", "artifact_manager");

    @Value("${security.access.enforce:false}")
    private boolean enforceAccess;

    @Value("${security.access.require-authentication:false}")
    private boolean requireAuthentication;

    public RequestAccessContext fromRequest(HttpServletRequest request) {
        String userId = trimToNull(firstHeader(request, "X-User-Id", "X-Actor", "X-User"));
        String actor = trimToNull(firstHeader(request, "X-Actor", "X-User", "X-User-Id"));
        String organizationId = trimToNull(firstHeader(request, "X-Org-Id", "X-Organization-Id"));
        String roleHeader = firstHeader(request, "X-Roles", "X-Role");
        String projectHeader = firstHeader(request, "X-Project-Ids", "X-Project-Id");
        boolean authenticated = userId != null || actor != null || roleHeader != null || organizationId != null;

        if (!authenticated) {
            if (requireAuthentication || enforceAccess) {
                throw new AccessDeniedException("Authentication headers are required");
            }
            return RequestAccessContext.local();
        }

        return new RequestAccessContext(
            userId == null ? actor : userId,
            actor == null ? userId : actor,
            organizationId == null ? "local" : organizationId,
            RequestAccessContext.normalizeRoles(roleHeader),
            RequestAccessContext.normalizeProjectIds(projectHeader),
            true
        );
    }

    public void assertProjectAccess(RequestAccessContext context, String projectId) {
        if (!enforceAccess || projectId == null || projectId.isBlank()) {
            return;
        }
        if (!context.hasProjectAccess(projectId)) {
            throw new AccessDeniedException("User " + context.actor() + " cannot access project " + projectId);
        }
    }

    public void assertMutationAllowed(RequestAccessContext context, String projectId, String action) {
        assertProjectAccess(context, projectId);
        if (!enforceAccess) {
            return;
        }
        if (!context.hasAnyRole(DEFAULT_MUTATION_ROLES)) {
            throw new AccessDeniedException("Role " + context.primaryRole() + " cannot perform action " + action);
        }
    }

    public boolean isEnforceAccess() {
        return enforceAccess;
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
