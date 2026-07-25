package com.sql.logic.engine.infrastructure.interceptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sql.logic.engine.infrastructure.dao.UserLlmApiKeyDao;
import com.sql.logic.engine.infrastructure.po.UserLlmApiKey;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Validates Bearer tokens for OpenAI-compatible API endpoints.
 * Reuses the existing user_llm_api_key table to look up valid API keys.
 */
@Component
public class OpenAiAuthFilter implements Filter {

    private final UserLlmApiKeyDao apiKeyDao;

    public OpenAiAuthFilter(UserLlmApiKeyDao apiKeyDao) {
        this.apiKeyDao = apiKeyDao;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String path = req.getRequestURI();
        if (!path.startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            sendError(resp, 401, "Missing or invalid Authorization header");
            return;
        }

        String token = auth.substring(7);
        if (token.isBlank()) {
            sendError(resp, 401, "Empty API key");
            return;
        }

        UserLlmApiKey apiKey = apiKeyDao.selectOne(
                new LambdaQueryWrapper<UserLlmApiKey>()
                        .eq(UserLlmApiKey::getApiKey, token)
                        .eq(UserLlmApiKey::getStatus, 1));
        if (apiKey == null) {
            sendError(resp, 401, "Invalid API key");
            return;
        }

        req.setAttribute("openai_userId", apiKey.getUserId());
        chain.doFilter(request, response);
    }

    private void sendError(HttpServletResponse resp, int code, String message) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":{\"message\":\"" + message +
                "\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}");
    }
}
