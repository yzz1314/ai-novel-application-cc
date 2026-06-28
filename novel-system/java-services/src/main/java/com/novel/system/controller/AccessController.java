package com.novel.system.controller;

import com.novel.system.security.RequestAccessContextHolder;
import com.novel.system.service.AccessGovernanceService;
import com.novel.system.service.AccessIdentityService;
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

    private String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value).trim();
    }
}
