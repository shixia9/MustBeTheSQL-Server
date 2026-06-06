package com.sql.logic.engine.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.common.response.Result;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Global authentication filter for the API Gateway.
 *
 * Authenticates requests by reading the Sa-Token from cookie/header,
 * then looking up the login ID directly from Redis (Sa-Token stores
 * sessions in Redis via Redisson). Injects X-User-Id header for
 * downstream services.
 *
 * Whitelisted paths (login, register) pass through without authentication.
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String TOKEN_NAME = "satoken";

    private static final Set<String> WHITE_LIST = Set.of(
            "/api/v1/user/login",
            "/api/v1/user/register"
    );

    /** Sa-Token Redis key for token -> loginId mapping: satoken:{tokenValue} */
    private static final String SA_TOKEN_TOKEN_KEY_PREFIX = "satoken:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthGlobalFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Whitelisted paths: no auth required
        if (WHITE_LIST.contains(path)) {
            return chain.filter(exchange);
        }

        // Try to get the Sa-Token value from header or cookie
        String tokenValue = extractTokenValue(exchange);

        if (tokenValue == null || tokenValue.isEmpty()) {
            return writeUnauthorizedResponse(exchange, "Not logged in");
        }

        // Look up loginId from Redis directly
        String loginId = getLoginIdByToken(tokenValue);

        if (loginId == null || loginId.isEmpty()) {
            return writeUnauthorizedResponse(exchange, "Invalid or expired session");
        }

        // Inject X-User-Id header for downstream service
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(USER_ID_HEADER, loginId)
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    /**
     * Extract satoken value from request header or cookie.
     */
    private String extractTokenValue(ServerWebExchange exchange) {
        // Try header first
        String tokenFromHeader = exchange.getRequest().getHeaders().getFirst(TOKEN_NAME);
        if (tokenFromHeader != null && !tokenFromHeader.isEmpty()) {
            return tokenValue(tokenFromHeader);
        }

        // Try cookie
        HttpHeaders headers = exchange.getRequest().getHeaders();
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("cookie"))
                .flatMap(e -> e.getValue().stream())
                .filter(cookie -> cookie.contains(TOKEN_NAME + "="))
                .map(cookie -> {
                    String[] parts = cookie.split(TOKEN_NAME + "=");
                    if (parts.length > 1) {
                        String val = parts[1].split(";")[0].trim();
                        return val.isEmpty() ? null : tokenValue(val);
                    }
                    return null;
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Get loginId by token value directly from Redis.
     * Sa-Token stores "satoken:{tokenValue}" -> "{loginType}:{loginId}" in Redis.
     */
    private String getLoginIdByToken(String tokenValue) {
        try {
            // Sa-Token stores: satoken:{token} -> "0:{userId}" (loginType:userId)
            String key = SA_TOKEN_TOKEN_KEY_PREFIX + tokenValue;
            String value = redisTemplate.opsForValue().get(key);
            if (value != null && !value.isEmpty()) {
                // Value format: "0:12345" where 0 is the login type and 12345 is the user ID
                // Sa-Token stores: loginType + ":" + loginId
                int colonIndex = value.indexOf(':');
                if (colonIndex > 0) {
                    return value.substring(colonIndex + 1);
                }
                return value;
            }
        } catch (Exception e) {
            // Redis connection issue — fail open is safer than locking everyone out
            // Log would be better but we're in a reactive filter
        }
        return null;
    }

    /**
     * Strip quotes and whitespace from token value.
     */
    private String tokenValue(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private Mono<Void> writeUnauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Result<?> result = Result.error(401, message);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":401,\"message\":\"Unauthorized\"}".getBytes();
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100; // High priority: auth filter runs early
    }
}