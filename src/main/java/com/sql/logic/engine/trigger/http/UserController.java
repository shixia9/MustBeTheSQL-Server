package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.trigger.http.dto.LoginRequest;
import com.sql.logic.engine.trigger.http.dto.RegisterRequest;
import com.sql.logic.engine.trigger.http.response.Result;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserAppService userAppService;

    private static final Integer DEFAULT_LOGIN_SESSION_TIMEOUT = 60 * 60 * 24 * 7;

    public UserController(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    @PostMapping("/login")
    public Result<UserInfo> login(@RequestBody LoginRequest request) {
        try {
            UserInfo user = userAppService.login(request.getEmail(), request.getPassword());
            // Hide password in response
            user.setPassword(null);
            StpUtil.login(user.getId(), new SaLoginParameter()
                    .setIsLastingCookie(request.getRememberMe())  // remember me
                    .setTimeout(DEFAULT_LOGIN_SESSION_TIMEOUT));  // token expiration: 7 days
            return Result.success(user);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "Internal server error: " + e.getMessage());
        }
    }

    @PostMapping("/register")
    public Result<UserInfo> register(@RequestBody RegisterRequest request) {
        try {
            UserInfo user = userAppService.register(request.getUsername(), request.getPassword(), request.getEmail());
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/logout")
    public Result<Boolean> logout() {
        StpUtil.logout();
        return Result.success(true);
    }

    @PostMapping("/updateKeys")
    public Result<Void> updateKeys(@RequestParam Long userId, @RequestParam(required = false) String apiKey, @RequestParam(required = false) String secretKey) {
        try {
            userAppService.updateKeys(userId, apiKey, secretKey);
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }
}
