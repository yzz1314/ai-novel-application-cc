package com.novel.system.service;

import com.novel.system.entity.AccessOrganization;
import com.novel.system.entity.AccessUser;
import com.novel.system.repository.AccessOrganizationRepository;
import com.novel.system.repository.AccessUserRepository;
import com.novel.system.security.RequestAccessContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccessIdentityService {

    private static final String ACTIVE = "ACTIVE";

    private final AccessUserRepository accessUserRepository;
    private final AccessOrganizationRepository accessOrganizationRepository;
    private final ProjectAccessService projectAccessService;

    @Transactional
    public void recordIdentity(RequestAccessContext context) {
        if (context == null || !context.authenticated()) {
            return;
        }
        AccessOrganization organization = accessOrganizationRepository
            .findById(context.organizationId())
            .orElseGet(AccessOrganization::new);
        organization.setId(context.organizationId());
        organization.setName(context.organizationId());
        organization.setStatus(ACTIVE);
        accessOrganizationRepository.save(organization);

        AccessUser user = accessUserRepository.findById(context.userId()).orElseGet(AccessUser::new);
        user.setId(context.userId());
        user.setDisplayName(context.actor());
        user.setOrganizationId(context.organizationId());
        user.setStatus(ACTIVE);
        user.setLastSeenAt(LocalDateTime.now());
        accessUserRepository.save(user);
    }

    public Map<String, Object> currentIdentity(RequestAccessContext context) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticated", context.authenticated());
        response.put("userId", context.userId());
        response.put("actor", context.actor());
        response.put("organizationId", context.organizationId());
        response.put("roles", context.roles());
        response.put("projectIds", context.projectIds());
        response.put("user", accessUserRepository.findById(context.userId()).map(this::userResponse).orElse(null));
        response.put("organization", accessOrganizationRepository.findById(context.organizationId()).map(this::organizationResponse).orElse(null));
        response.put("projectMemberships", projectAccessService.listUserMemberships(context.userId()));
        return response;
    }

    private Map<String, Object> userResponse(AccessUser user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("displayName", user.getDisplayName());
        response.put("organizationId", user.getOrganizationId());
        response.put("status", user.getStatus());
        response.put("lastSeenAt", user.getLastSeenAt());
        response.put("createdAt", user.getCreatedAt());
        response.put("updatedAt", user.getUpdatedAt());
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
}
