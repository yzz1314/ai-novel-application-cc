package com.novel.system.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.exception.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class JwtAccessTokenService {

    private static final Set<String> SUPPORTED_ALGORITHMS = Set.of("HS256", "HS384", "HS512");

    private final ObjectMapper objectMapper;

    @Value("${security.access.jwt.enabled:false}")
    private boolean enabled;

    @Value("${security.access.jwt.require-bearer:false}")
    private boolean requireBearer;

    @Value("${security.access.jwt.secret:}")
    private String secret;

    @Value("${security.access.jwt.issuer:}")
    private String issuer;

    @Value("${security.access.jwt.audience:}")
    private String audience;

    @Value("${security.access.jwt.clock-skew-seconds:60}")
    private long clockSkewSeconds;

    public Optional<RequestAccessContext> fromRequest(HttpServletRequest request) {
        String token = bearerToken(request);
        if (token == null) {
            return Optional.empty();
        }
        if (!enabled) {
            throw new AccessDeniedException("JWT bearer authentication is not enabled");
        }
        return Optional.of(verify(token));
    }

    public boolean isBearerRequired() {
        return enabled && requireBearer;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private RequestAccessContext verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new AccessDeniedException("Invalid bearer token");
            }

            Map<String, Object> header = readJsonPart(parts[0]);
            Map<String, Object> claims = readJsonPart(parts[1]);
            String algorithm = stringValue(header.get("alg"));
            if (!SUPPORTED_ALGORITHMS.contains(algorithm)) {
                throw new AccessDeniedException("Unsupported JWT algorithm");
            }
            verifySignature(algorithm, parts[0] + "." + parts[1], parts[2]);
            validateClaims(claims);
            return contextFromClaims(claims);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            throw new AccessDeniedException("Invalid bearer token");
        }
    }

    private void verifySignature(String algorithm, String signingInput, String encodedSignature) throws Exception {
        byte[] secretBytes = secretBytes();
        Mac mac = Mac.getInstance(macAlgorithm(algorithm));
        mac.init(new SecretKeySpec(secretBytes, macAlgorithm(algorithm)));
        byte[] expected = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] actual = Base64.getUrlDecoder().decode(encodedSignature);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new AccessDeniedException("Invalid bearer token signature");
        }
    }

    private byte[] secretBytes() {
        String configured = secret == null ? "" : secret.trim();
        if (configured.isBlank()) {
            throw new AccessDeniedException("JWT secret is not configured");
        }
        if (configured.startsWith("base64:")) {
            return Base64.getDecoder().decode(configured.substring("base64:".length()));
        }
        return configured.getBytes(StandardCharsets.UTF_8);
    }

    private void validateClaims(Map<String, Object> claims) {
        Instant now = Instant.now();
        long skew = Math.max(0, clockSkewSeconds);
        Number exp = numberValue(claims.get("exp"));
        if (exp != null && now.minusSeconds(skew).getEpochSecond() >= exp.longValue()) {
            throw new AccessDeniedException("Bearer token has expired");
        }
        Number nbf = numberValue(claims.get("nbf"));
        if (nbf != null && now.plusSeconds(skew).getEpochSecond() < nbf.longValue()) {
            throw new AccessDeniedException("Bearer token is not active yet");
        }
        if (issuer != null && !issuer.isBlank() && !issuer.equals(stringValue(claims.get("iss")))) {
            throw new AccessDeniedException("Bearer token issuer is invalid");
        }
        if (audience != null && !audience.isBlank() && !claimContains(claims.get("aud"), audience)) {
            throw new AccessDeniedException("Bearer token audience is invalid");
        }
    }

    private RequestAccessContext contextFromClaims(Map<String, Object> claims) {
        String userId = firstString(claims, "sub", "user_id", "userId", "uid");
        if (userId == null || userId.isBlank()) {
            throw new AccessDeniedException("Bearer token subject is required");
        }
        String actor = firstString(claims, "name", "preferred_username", "actor", "email", "username");
        String organizationId = firstString(claims, "org_id", "organization_id", "organizationId", "tenant", "tenant_id");
        return new RequestAccessContext(
            userId,
            actor == null || actor.isBlank() ? userId : actor,
            organizationId == null || organizationId.isBlank() ? "local" : organizationId,
            rolesFromClaims(claims),
            projectIdsFromClaims(claims),
            true
        );
    }

    private Set<String> rolesFromClaims(Map<String, Object> claims) {
        Object value = firstPresent(claims, "roles", "role", "authorities", "scope", "scp");
        Set<String> roles = normalizeClaimSet(value, true);
        return roles.isEmpty() ? Set.of("viewer") : roles;
    }

    private Set<String> projectIdsFromClaims(Map<String, Object> claims) {
        Object value = firstPresent(claims, "project_ids", "projectIds", "projects", "project_id", "projectId");
        return normalizeClaimSet(value, false);
    }

    private Set<String> normalizeClaimSet(Object value, boolean roles) {
        List<String> items = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                items.add(stringValue(item));
            }
        } else {
            String raw = stringValue(value);
            if (!raw.isBlank()) {
                Collections.addAll(items, raw.split("[,\\s]+"));
            }
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            normalized.add(roles ? RequestAccessContext.normalizeRole(item) : item.trim());
        }
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }

    private Object firstPresent(Map<String, Object> claims, String... keys) {
        for (String key : keys) {
            Object value = claims.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstString(Map<String, Object> claims, String... keys) {
        Object value = firstPresent(claims, keys);
        String text = stringValue(value);
        return text.isBlank() ? null : text;
    }

    private boolean claimContains(Object value, String expected) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (expected.equals(stringValue(item))) {
                    return true;
                }
            }
            return false;
        }
        return expected.equals(stringValue(value));
    }

    private Map<String, Object> readJsonPart(String encoded) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        return objectMapper.readValue(decoded, new TypeReference<>() {});
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return null;
        }
        String token = trimmed.substring("bearer ".length()).trim();
        return token.isBlank() ? null : token;
    }

    private String macAlgorithm(String algorithm) {
        return switch (algorithm) {
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> "HmacSHA256";
        };
    }

    private Number numberValue(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            String text = stringValue(value);
            return text.isBlank() ? null : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
