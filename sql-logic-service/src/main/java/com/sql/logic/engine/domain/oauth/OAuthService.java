package com.sql.logic.engine.domain.oauth;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * GitHub OAuth SSO service.
 * <p>
 * Handles the OAuth2 authorization code flow:
 * <ol>
 *   <li>Redirect user to GitHub's authorize endpoint</li>
 *   <li>Handle the callback with an authorization code</li>
 *   <li>Exchange the code for an access token</li>
 *   <li>Fetch the user's GitHub profile (email + username)</li>
 *   <li>Create or lookup local user, establish Sa-Token session</li>
 * </ol>
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

    private static final String GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_URL = "https://api.github.com/user";
    private static final int DEFAULT_LOGIN_SESSION_TIMEOUT = 60 * 60 * 24 * 7;

    @Value("${oauth.github.client-id:}")
    private String clientId;

    @Value("${oauth.github.client-secret:}")
    private String clientSecret;

    @Value("${oauth.github.redirect-uri:http://localhost:8080/api/v1/oauth/github/callback}")
    private String redirectUri;

    @Value("${oauth.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    private final UserAppService userAppService;
    private final ObjectMapper objectMapper;

    public OAuthService(UserAppService userAppService, ObjectMapper objectMapper) {
        this.userAppService = userAppService;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    /** Build the GitHub OAuth authorize URL. */
    public String buildAuthorizeUrl() {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub OAuth is not configured (set oauth.github.client-id and client-secret)");
        }
        String state = UUID.randomUUID().toString().substring(0, 12);
        return GITHUB_AUTHORIZE_URL + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode("user:email")
                + "&state=" + urlEncode(state);
    }

    /**
     * Handle the OAuth callback: exchange code for token, fetch user profile,
     * create or login user, return the frontend redirect URL with session cookie set.
     */
    public String handleCallback(String code, String state) {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub OAuth is not configured");
        }
        // 1. Exchange code for access token
        String accessToken = exchangeCodeForToken(code);
        // 2. Fetch GitHub user profile
        GithubUser githubUser = fetchUserProfile(accessToken);
        // 3. Find or create local user
        UserInfo user = findOrCreateUser(githubUser);
        // 4. Establish Sa-Token session
        StpUtil.login(user.getId(), new SaLoginParameter()
                .setTimeout(DEFAULT_LOGIN_SESSION_TIMEOUT));
        StpUtil.getSession().set(user.getId().toString(), user);
        log.info("[OAuthService] GitHub OAuth login success: userId={}, githubLogin={}", user.getId(), githubUser.login);
        // 5. Redirect to frontend (Vite dev server / production build)
        return frontendUrl + "/dashboard";
    }

    private String exchangeCodeForToken(String code) {
        try {
            String body = "client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(redirectUri);

            HttpURLConnection conn = (HttpURLConnection) URI.create(GITHUB_TOKEN_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            JsonNode resp = objectMapper.readTree(conn.getInputStream());
            if (resp.has("error")) {
                throw new RuntimeException("GitHub token exchange failed: " + resp.get("error").asText());
            }
            String token = resp.get("access_token").asText();
            if (token == null || token.isBlank()) {
                throw new RuntimeException("GitHub returned no access_token");
            }
            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to exchange GitHub OAuth code: " + e.getMessage(), e);
        }
    }

    private GithubUser fetchUserProfile(String accessToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(GITHUB_USER_URL).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");

            JsonNode userNode = objectMapper.readTree(conn.getInputStream());
            String login = userNode.has("login") ? userNode.get("login").asText() : null;
            String email = userNode.has("email") && !userNode.get("email").isNull()
                    ? userNode.get("email").asText() : null;
            String name = userNode.has("name") && !userNode.get("name").isNull()
                    ? userNode.get("name").asText() : login;
            String avatarUrl = userNode.has("avatar_url") && !userNode.get("avatar_url").isNull()
                    ? userNode.get("avatar_url").asText() : null;

            // If email is not public, fetch it separately from /user/emails
            if (email == null) {
                email = fetchPrimaryEmail(accessToken);
            }

            return new GithubUser(login, email, name, avatarUrl);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch GitHub user profile: " + e.getMessage(), e);
        }
    }

    private String fetchPrimaryEmail(String accessToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create("https://api.github.com/user/emails").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");

            JsonNode emails = objectMapper.readTree(conn.getInputStream());
            for (JsonNode e : emails) {
                if (e.has("primary") && e.get("primary").asBoolean()
                        && e.has("verified") && e.get("verified").asBoolean()) {
                    return e.get("email").asText();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[OAuthService] Failed to fetch GitHub emails: {}", e.getMessage());
            return null;
        }
    }

    private UserInfo findOrCreateUser(GithubUser githubUser) {
        // Try to find by email first
        if (githubUser.email != null) {
            try {
                return userAppService.getUserByEmail(githubUser.email);
            } catch (Exception e) {
                // Not found by email, fall through to create
            }
        }
        // Try by GitHub login as username
        if (githubUser.login != null) {
            try {
                return userAppService.getUserByEmail(githubUser.login);
            } catch (Exception ignored) {}
        }
        // Create a new user with a random password (they'll use OAuth going forward)
        try {
            String username = githubUser.login != null ? githubUser.login : "gh_" + UUID.randomUUID().toString().substring(0, 6);
            try {
                return userAppService.register(username, UUID.randomUUID().toString(), githubUser.email);
            } catch (Exception e) {
                // Username taken, append suffix
                return userAppService.register(username + "_" + UUID.randomUUID().toString().substring(0, 4),
                        UUID.randomUUID().toString(), githubUser.email);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create user from GitHub OAuth: " + e.getMessage(), e);
        }
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record GithubUser(String login, String email, String name, String avatarUrl) {}
}
