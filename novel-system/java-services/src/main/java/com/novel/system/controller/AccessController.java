package com.novel.system.controller;

import com.novel.system.security.RequestAccessContextHolder;
import com.novel.system.service.AccessGovernanceService;
import com.novel.system.service.AccessIdentityService;
import com.novel.system.service.AccessRolePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/access")
@RequiredArgsConstructor
public class AccessController {

    private final AccessIdentityService accessIdentityService;
    private final AccessGovernanceService accessGovernanceService;
    private final AccessRolePolicyService accessRolePolicyService;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentAccessContext() {
        return ResponseEntity.ok(accessIdentityService.currentIdentity(RequestAccessContextHolder.current()));
    }

    @GetMapping("/organizations")
    public ResponseEntity<List<Map<String, Object>>> listOrganizations() {
        return ResponseEntity.ok(accessGovernanceService.listOrganizations());
    }

    @GetMapping("/organizations/{organizationId}/members")
    public ResponseEntity<List<Map<String, Object>>> listOrganizationMembers(@PathVariable String organizationId) {
        return ResponseEntity.ok(accessGovernanceService.listOrganizationMembers(organizationId));
    }

    @PostMapping("/organizations/{organizationId}/members")
    public ResponseEntity<Map<String, Object>> grantOrganizationMember(
            @PathVariable String organizationId,
            @RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(accessGovernanceService.organizationMemberResponse(
            accessGovernanceService.grantOrganizationMember(
                RequestAccessContextHolder.current(),
                organizationId,
                stringValue(request == null ? null : request.get("userId"), ""),
                stringValue(request == null ? null : request.get("displayName"), null),
                stringValue(request == null ? null : request.get("role"), "viewer")
            )
        ));
    }

    @DeleteMapping("/organizations/{organizationId}/members/{userId}")
    public ResponseEntity<Map<String, Object>> revokeOrganizationMember(
            @PathVariable String organizationId,
            @PathVariable String userId) {
        return ResponseEntity.ok(accessGovernanceService.organizationMemberResponse(
            accessGovernanceService.revokeOrganizationMember(
                RequestAccessContextHolder.current(),
                organizationId,
                userId
            )
        ));
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> listAuditEvents(
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(accessGovernanceService.listAuditEvents(organizationId, projectId, actorId, limit));
    }

    @GetMapping("/role-policies")
    public ResponseEntity<List<Map<String, Object>>> listRolePolicies() {
        return ResponseEntity.ok(accessRolePolicyService.listPolicies());
    }

    @PatchMapping("/role-policies/{actionKey}")
    public ResponseEntity<Map<String, Object>> updateRolePolicy(
            @PathVariable String actionKey,
            @RequestBody Map<String, Object> request) {
        var context = RequestAccessContextHolder.current();
        var policy = accessRolePolicyService.updatePolicy(
            context,
            actionKey,
            listValue(request == null ? null : request.get("allowedRoles")),
            stringValue(request == null ? null : request.get("description"), null)
        );
        accessGovernanceService.recordAudit(
            "role_policy_updated",
            context,
            context.organizationId(),
            null,
            null,
            context.organizationId(),
            "update_role_policy",
            "success",
            "actionKey=" + policy.getActionKey() + ", allowedRoles=" + policy.getAllowedRoles()
        );
        return ResponseEntity.ok(accessRolePolicyService.toResponse(policy));
    }

    private String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value).trim();
    }

    private List<?> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return List.of();
    }
}
