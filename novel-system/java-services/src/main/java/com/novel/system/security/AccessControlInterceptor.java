package com.novel.system.security;

import com.novel.system.service.AccessIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AccessControlInterceptor implements HandlerInterceptor {

    private final AccessControlService accessControlService;
    private final AccessIdentityService accessIdentityService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RequestAccessContext context = accessControlService.fromRequest(request);
        String projectId = projectId(request);
        context = accessControlService.resolveProjectAccess(context, projectId);
        RequestAccessContextHolder.set(context);
        accessIdentityService.recordIdentity(context);
        accessControlService.assertProjectAccess(context, projectId);
        if (projectId != null && isMutation(request.getMethod())) {
            accessControlService.assertMutationAllowed(context, projectId, request.getMethod() + " " + request.getRequestURI());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestAccessContextHolder.clear();
    }

    @SuppressWarnings("unchecked")
    private String projectId(HttpServletRequest request) {
        Object attributes = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attributes instanceof Map<?, ?> map) {
            Object value = map.get("projectId");
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private boolean isMutation(String method) {
        return "POST".equalsIgnoreCase(method)
            || "PUT".equalsIgnoreCase(method)
            || "PATCH".equalsIgnoreCase(method)
            || "DELETE".equalsIgnoreCase(method);
    }
}
