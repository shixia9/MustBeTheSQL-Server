package com.sql.logic.engine.common.context;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Security context utility that extracts user identity from the request header
 * injected by the API Gateway (X-User-Id). This replaces direct Sa-Token calls
 * in controllers after migrating to a gateway-based authentication pattern.
 */
public class SecurityContext {

    private static final String USER_ID_HEADER = "X-User-Id";

    private SecurityContext() {}

    /**
     * Get the current authenticated user ID from the gateway-injected header.
     *
     * @return the authenticated user ID
     * @throws IllegalStateException if no user identity is found in the request
     */
    public static Long getCurrentUserId() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String userId = attrs.getRequest().getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isEmpty()) {
                return Long.valueOf(userId);
            }
        }
        throw new IllegalStateException("No authenticated user found. Missing " + USER_ID_HEADER + " header.");
    }

    /**
     * Check if a user identity header is present in the current request.
     *
     * @return true if the X-User-Id header is present and non-empty
     */
    public static boolean isAuthenticated() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String userId = attrs.getRequest().getHeader(USER_ID_HEADER);
            return userId != null && !userId.isEmpty();
        }
        return false;
    }
}