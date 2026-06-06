package com.sql.logic.engine.infrastructure.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Defense-in-depth security filter that validates the X-User-Id header
 * injected by the API Gateway. This ensures that even if the service is
 * accessed directly (bypassing the gateway), unauthenticated requests
 * are still rejected.
 *
 * In production, the gateway should be the only entry point. This filter
 * acts as a safety net for internal network access.
 *
 * Can be disabled via engine.auth.internal-filter-enabled=false for local development.
 */
@Component
@Order(1)
public class InternalAuthFilter implements Filter {

    private static final String USER_ID_HEADER = "X-User-Id";

    private static final Set<String> WHITE_LIST = Set.of(
            "/api/v1/user/login",
            "/api/v1/user/register"
    );

    @Value("${engine.auth.internal-filter-enabled:true}")
    private boolean filterEnabled;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // When disabled (e.g., local development without Gateway), skip the filter
        if (!filterEnabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // Whitelisted paths: login and register don't require auth
        if (WHITE_LIST.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Skip CORS preflight requests
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String userIdHeader = httpRequest.getHeader(USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.isEmpty()) {
            ((HttpServletResponse) response).sendError(401, "Unauthorized: Missing authentication header");
            return;
        }

        // Validate that the header is a valid Long
        try {
            Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            ((HttpServletResponse) response).sendError(401, "Unauthorized: Invalid user identity");
            return;
        }

        chain.doFilter(request, response);
    }
}