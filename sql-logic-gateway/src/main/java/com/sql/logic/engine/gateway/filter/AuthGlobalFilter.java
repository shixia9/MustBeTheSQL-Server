package com.sql.logic.engine.gateway.filter;

import cn.dev33.satoken.stp.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.common.response.Result;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
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
 * Authenticates requests using Sa-Token (which reads sessions from Redis),
 * then injects the X-User-Id header for downstream services to consume.
 *
 * Whitelisted paths (login, register) pass through without authentication.
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_HEADER = "X-User-Id";

    private static final Set<String> WHITE_LIST = Set.of(
            "/api/v1/user/login",
            "/api/v1/user/register"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Whitelisted paths: no auth required
        if (WHITE_LIST.contains(path)) {
            return chain.filter(exchange);
        }

        // Try to get the Sa-Token from cookie or header
        String tokenValue = exchange.getRequest().getHeaders().getFirst("satoken");
        if (tokenValue == null) {
            // Try cookie
            HttpHeaders headers = exchange.getRequest().getHeaders();
            tokenValue = headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("cookie"))
                    .flatMap(e -> e.getValue().stream())
                    .filter(cookie -> cookie.contains("satoken="))
                    .map(cookie -> {
                        String[] parts = cookie.split("satoken=");
                        if (parts.length > 1) {
                            String val = parts[1].split(";")[0].trim();
                            return val.isEmpty() ? null : val;
                        }
                        return null;
                    })
                    .findFirst()
                    .orElse(null);
        }

        if (tokenValue == null || tokenValue.isEmpty()) {
            return writeUnauthorizedResponse(exchange, "Not logged in");
        }

        // Validate the token and get the login ID
        Object loginId;
        try {
            // Use Sa-Token's programmatic API to validate the token
            // StpUtil uses Redis (via Redisson) for session storage
            SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
            if (tokenInfo == null || tokenInfo.getLoginId() == null) {
                return writeUnauthorizedResponse(exchange, "Invalid or expired session");
            }
            loginId = tokenInfo.getLoginId();
        } catch (Exception e) {
            return writeUnauthorizedResponse(exchange, "Authentication failed: " + e.getMessage());
        }

        // Inject X-User-Id header for downstream service
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(USER_ID_HEADER, String.valueOf(loginId))
                .build();

        return chain.filter(exchange.mutate().request(request).build());
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