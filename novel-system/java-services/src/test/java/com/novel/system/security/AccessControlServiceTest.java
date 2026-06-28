package com.novel.system.security;

import com.novel.system.exception.AccessDeniedException;
import com.novel.system.service.AccessRolePolicyService;
import com.novel.system.service.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class AccessControlServiceTest {

    @Test
    void buildsRequestContextFromHeaders() {
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "u-1");
        request.addHeader("X-Actor", "Alice");
        request.addHeader("X-Org-Id", "org-1");
        request.addHeader("X-Roles", "admin,artifact_manager");
        request.addHeader("X-Project-Ids", "project-a,project-b");

        RequestAccessContext context = service.fromRequest(request);

        assertThat(context.authenticated()).isTrue();
        assertThat(context.userId()).isEqualTo("u-1");
        assertThat(context.actor()).isEqualTo("Alice");
        assertThat(context.organizationId()).isEqualTo("org-1");
        assertThat(context.roles()).containsExactlyInAnyOrder("admin", "artifact_manager");
        assertThat(context.hasProjectAccess("project-b")).isTrue();
        assertThat(context.hasProjectAccess("project-c")).isFalse();
    }

    @Test
    void rejectsMissingAuthenticationWhenRequired() {
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService());
        ReflectionTestUtils.setField(service, "requireAuthentication", true);

        assertThatThrownBy(() -> service.fromRequest(new MockHttpServletRequest()))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Authentication headers are required");
    }

    @Test
    void buildsRequestContextFromSignedJwtBearerToken() {
        JwtAccessTokenService jwt = jwtService("jwt-secret", true, false);
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService(), jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtToken("jwt-secret", Map.of(
            "sub", "u-jwt",
            "name", "Token Alice",
            "org_id", "org-jwt",
            "roles", java.util.List.of("admin", "artifact_manager"),
            "project_ids", java.util.List.of("project-token"),
            "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));
        request.addHeader("X-User-Id", "spoofed");
        request.addHeader("X-Roles", "owner");

        RequestAccessContext context = service.fromRequest(request);

        assertThat(context.authenticated()).isTrue();
        assertThat(context.userId()).isEqualTo("u-jwt");
        assertThat(context.actor()).isEqualTo("Token Alice");
        assertThat(context.organizationId()).isEqualTo("org-jwt");
        assertThat(context.roles()).containsExactlyInAnyOrder("admin", "artifact_manager");
        assertThat(context.hasProjectAccess("project-token")).isTrue();
    }

    @Test
    void rejectsHeaderFallbackWhenBearerTokenIsRequired() {
        JwtAccessTokenService jwt = jwtService("jwt-secret", true, true);
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService(), jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "u-1");
        request.addHeader("X-Roles", "owner");

        assertThatThrownBy(() -> service.fromRequest(request))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Bearer token is required");
    }

    @Test
    void rejectsJwtWithInvalidSignature() {
        JwtAccessTokenService jwt = jwtService("jwt-secret", true, false);
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService(), jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtToken("wrong-secret", Map.of(
            "sub", "u-jwt",
            "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));

        assertThatThrownBy(() -> service.fromRequest(request))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Invalid bearer token signature");
    }

    @Test
    void rejectsExpiredJwtBearerToken() {
        JwtAccessTokenService jwt = jwtService("jwt-secret", true, false);
        ReflectionTestUtils.setField(jwt, "clockSkewSeconds", 0L);
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService(), jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtToken("jwt-secret", Map.of(
            "sub", "u-jwt",
            "exp", Instant.now().minusSeconds(30).getEpochSecond()
        )));

        assertThatThrownBy(() -> service.fromRequest(request))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Bearer token has expired");
    }

    @Test
    void validatesJwtIssuerAndAudienceWhenConfigured() {
        JwtAccessTokenService jwt = jwtService("jwt-secret", true, false);
        ReflectionTestUtils.setField(jwt, "issuer", "novel-system");
        ReflectionTestUtils.setField(jwt, "audience", "novel-users");
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService(), jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtToken("jwt-secret", Map.of(
            "sub", "u-jwt",
            "iss", "novel-system",
            "aud", java.util.List.of("novel-users", "other-client"),
            "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));

        RequestAccessContext context = service.fromRequest(request);

        assertThat(context.userId()).isEqualTo("u-jwt");
    }

    @Test
    void rejectsJwtWithInvalidAudience() {
        JwtAccessTokenService jwt = jwtService("jwt-secret", true, false);
        ReflectionTestUtils.setField(jwt, "audience", "novel-users");
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService(), jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtToken("jwt-secret", Map.of(
            "sub", "u-jwt",
            "aud", "other-client",
            "exp", Instant.now().plusSeconds(300).getEpochSecond()
        )));

        assertThatThrownBy(() -> service.fromRequest(request))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Bearer token audience is invalid");
    }

    @Test
    void rejectsUnauthorizedProjectWhenEnforced() {
        AccessControlService service = service(mock(ProjectAccessService.class), mockPolicyService());
        ReflectionTestUtils.setField(service, "enforceAccess", true);
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Alice",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );

        assertThatThrownBy(() -> service.assertProjectAccess(context, "project-b"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot access project project-b");
    }

    @Test
    void rejectsViewerMutationWhenEnforced() {
        AccessRolePolicyService policyService = mockPolicyService();
        AccessControlService service = service(mock(ProjectAccessService.class), policyService);
        ReflectionTestUtils.setField(service, "enforceAccess", true);
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Reader",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );

        assertThatThrownBy(() -> service.assertMutationAllowed(context, "project-a", "delete project"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot perform action");
    }

    @Test
    void resolvesProjectAccessThroughMembershipService() {
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        AccessControlService service = service(projectAccessService, mockPolicyService());
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Alice",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );
        RequestAccessContext resolved = context.withProjectAccess("project-b", "editor");
        when(projectAccessService.resolveProjectAccess(context, "project-b")).thenReturn(resolved);

        assertThat(service.resolveProjectAccess(context, "project-b")).isSameAs(resolved);
    }

    @Test
    void mutationPolicyCanAllowViewerWhenConfigured() {
        AccessRolePolicyService policyService = mock(AccessRolePolicyService.class);
        RequestAccessContext context = new RequestAccessContext(
            "u-1",
            "Reader",
            "org-1",
            RequestAccessContext.normalizeRoles("viewer"),
            RequestAccessContext.normalizeProjectIds("project-a"),
            true
        );
        AccessControlService service = service(mock(ProjectAccessService.class), policyService);
        ReflectionTestUtils.setField(service, "enforceAccess", true);

        service.assertMutationAllowed(context, "project-a", "patch");

        org.mockito.Mockito.verify(policyService).assertAllowed(
            context,
            AccessRolePolicyService.ACTION_PROJECT_MUTATION,
            "patch"
        );
    }

    private AccessControlService service(ProjectAccessService projectAccessService, AccessRolePolicyService policyService) {
        return service(projectAccessService, policyService, jwtService("", false, false));
    }

    private AccessControlService service(
            ProjectAccessService projectAccessService,
            AccessRolePolicyService policyService,
            JwtAccessTokenService jwtAccessTokenService) {
        return new AccessControlService(projectAccessService, policyService, jwtAccessTokenService);
    }

    private JwtAccessTokenService jwtService(String secret, boolean enabled, boolean requireBearer) {
        JwtAccessTokenService service = new JwtAccessTokenService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "requireBearer", requireBearer);
        ReflectionTestUtils.setField(service, "secret", secret);
        ReflectionTestUtils.setField(service, "clockSkewSeconds", 60L);
        return service;
    }

    private String jwtToken(String secret, Map<String, Object> claims) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = base64Url(objectMapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AccessRolePolicyService mockPolicyService() {
        AccessRolePolicyService policyService = mock(AccessRolePolicyService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            RequestAccessContext context = invocation.getArgument(0);
            String action = invocation.getArgument(2);
            if (context == null || !context.hasAnyRole(java.util.Set.of("owner", "admin", "editor", "artifact_manager"))) {
                throw new AccessDeniedException("Role " + (context == null ? "anonymous" : context.primaryRole()) + " cannot perform action " + action);
            }
            return null;
        }).when(policyService).assertAllowed(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(AccessRolePolicyService.ACTION_PROJECT_MUTATION),
            org.mockito.ArgumentMatchers.anyString()
        );
        return policyService;
    }
}
