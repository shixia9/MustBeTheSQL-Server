package com.sql.logic.engine.trigger.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Simple token-bucket rate limiter for OpenAI-compatible API endpoints.
 * Uses Caffeine cache to track request counts per user/token.
 * Default: 60 requests per minute per user.
 */
@Component
public class RateLimitInterceptor implements Filter {

    private final Cache<String, RateBucket> bucketCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    private static final int DEFAULT_RPM = 60;

    private record RateBucket(long windowStart, int count) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;

        // Only rate-limit /v1/ endpoints
        if (!req.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        // Use userId from auth filter
        Long userId = (Long) req.getAttribute("openai_userId");
        String key = userId != null ? "user:" + userId : "ip:" + req.getRemoteAddr();

        long now = System.currentTimeMillis();
        long windowMs = 60_000; // 1 minute window

        RateBucket bucket = bucketCache.get(key, k -> new RateBucket(now, 0));

        if (bucket == null || now - bucket.windowStart() > windowMs) {
            bucket = new RateBucket(now, 1);
            bucketCache.put(key, bucket);
        } else if (bucket.count() >= DEFAULT_RPM) {
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.setStatus(429);
            resp.setContentType("application/json");
            resp.setHeader("Retry-After", "60");
            resp.getWriter().write("{\"error\":{\"message\":\"Rate limit exceeded. " +
                    DEFAULT_RPM + " requests per minute allowed.\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}");
            return;
        } else {
            bucketCache.put(key, new RateBucket(bucket.windowStart(), bucket.count() + 1));
        }

        chain.doFilter(request, response);
    }
}
