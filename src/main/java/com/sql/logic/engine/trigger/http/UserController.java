package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.trigger.http.dto.LoginRequest;
import com.sql.logic.engine.trigger.http.dto.RegisterRequest;
import com.sql.logic.engine.trigger.http.dto.UpdateKeysRequest;
import com.sql.logic.engine.trigger.http.dto.UpdatePasswordRequest;
import com.sql.logic.engine.trigger.http.dto.UpdateProfileRequest;
import com.sql.logic.engine.trigger.http.response.Result;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @SaCheckLogin
    @PostMapping("/logout")
    public Result<Boolean> logout() {
        StpUtil.logout();
        return Result.success(true);
    }

    @SaCheckLogin
    @PostMapping("/updateKeys")
    public Result<Void> updateKeys(@RequestBody UpdateKeysRequest request) {
        try {
            Long loginUserId = StpUtil.getLoginIdAsLong();
            userAppService.updateKeys(loginUserId, request.getApiKey(), request.getBaseUrl());
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @SaCheckLogin
    @PostMapping("/updateProfile")
    public Result<UserInfo> updateProfile(@RequestBody UpdateProfileRequest request) {
        try {
            Long loginUserId = StpUtil.getLoginIdAsLong();
            UserInfo user = userAppService.updateProfile(loginUserId, request.getUsername(), request.getEmail());
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @SaCheckLogin
    @PostMapping("/updatePassword")
    public Result<Boolean> updatePassword(@RequestBody UpdatePasswordRequest request) {
        try {
            Long loginUserId = StpUtil.getLoginIdAsLong();
            userAppService.updatePassword(loginUserId, request.getOldPassword(), request.getNewPassword());
            StpUtil.logout(); // Force logout after password change
            return Result.success(true);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @SaCheckLogin
    @PostMapping("/uploadAvatar")
    public Result<UserInfo> uploadAvatar(@RequestParam("file") MultipartFile file) {
        try {
            Long loginUserId = StpUtil.getLoginIdAsLong();
            UserInfo user = userAppService.updateAvatar(loginUserId, file);
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @SaCheckLogin
    @PostMapping("/cancelAccount")
    public Result<Boolean> cancelAccount() {
        try {
            Long loginUserId = StpUtil.getLoginIdAsLong();
            userAppService.cancelAccount(loginUserId);
            StpUtil.logout();
            return Result.success(true);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @SaCheckLogin
    @GetMapping("/info")
    public Result<UserInfo> getUserInfo() {
        try {
            Long loginUserId = StpUtil.getLoginIdAsLong();
            UserInfo user = userAppService.getUserById(loginUserId);
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }
}
