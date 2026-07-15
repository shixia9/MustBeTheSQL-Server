package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.oauth.OAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GitHub OAuth SSO endpoints.
 */
@RestController
@RequestMapping("/api/v1/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthService oauthService;

    @Value("${oauth.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    public OAuthController(OAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping("/github/status")
    public Result<Map<String, Boolean>> status() {
        return Result.success(Map.of("configured", oauthService.isConfigured()));
    }

    @GetMapping("/github/authorize")
    public void authorize(HttpServletResponse response) throws IOException {
        try {
            String url = oauthService.buildAuthorizeUrl();
            response.sendRedirect(url);
        } catch (Exception e) {
            log.error("[OAuthController] GitHub authorize failed: {}", e.getMessage());
            response.sendError(500, "OAuth configuration error: " + e.getMessage());
        }
    }

    @GetMapping("/github/callback")
    public void callback(@RequestParam String code,
                         @RequestParam(required = false) String state,
                         HttpServletResponse response) throws IOException {
        try {
            String redirectPath = oauthService.handleCallback(code, state);
            // Redirect to the frontend application
            response.sendRedirect(redirectPath);
        } catch (Exception e) {
            log.error("[OAuthController] GitHub callback failed: {}", e.getMessage());
            response.sendRedirect(frontendUrl + "/?error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }
}
