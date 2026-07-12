package com.sql.logic.engine.infrastructure.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory per-user rate limiter for the Agent stream endpoint.
 * <p>
 * Activated by setting {@code agent.rate-limit.enabled=true} in application.yml.
 * Not meant for multi-instance deployments — use Redis-based rate limiting with
 * the gateway module for production clusters.
 */
@Component
@ConditionalOnProperty(value = "agent.rate-limit.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Window size in milliseconds (default 1 minute). */
    private static final long WINDOW_MS = 60_000;
    /** Max requests per window per user. */
    private static final int MAX_REQUESTS = 30;

    private final ConcurrentHashMap<Long, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String uri = req.getRequestURI();
        if (!uri.startsWith("/api/v1/agent/sql/stream")) {
            chain.doFilter(request, response);
            return;
        }

        Long userId = null;
        try {
            String idStr = (String) StpUtil.getLoginId();
            if (idStr != null && idStr.matches("\\d+")) {
                userId = Long.valueOf(idStr);
            }
        } catch (Exception ignored) {}

        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        WindowCounter c = counters.computeIfAbsent(userId, k -> new WindowCounter(now));
        synchronized (c) {
            if (now - c.windowStart > WINDOW_MS) {
                c.windowStart = now;
                c.count.set(0);
            }
            if (c.count.incrementAndGet() > MAX_REQUESTS) {
                log.warn("[RateLimit] User {} exceeded rate limit ({} req/min)", userId, c.count.get());
                res.setStatus(429);
                res.setHeader("Retry-After", "60");
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"code\":429,\"message\":\"Too many requests. Please try again later.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static class WindowCounter {
        volatile long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long start) { this.windowStart = start; }
    }
}
