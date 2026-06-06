package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.common.context.SecurityContext;
import com.sql.logic.engine.common.dto.LoginRequest;
import com.sql.logic.engine.common.dto.RegisterRequest;
import com.sql.logic.engine.common.dto.UpdateKeysRequest;
import com.sql.logic.engine.common.dto.UpdatePasswordRequest;
import com.sql.logic.engine.common.dto.UpdateProfileRequest;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.infrastructure.po.UserInfo;

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

    @PostMapping("/logout")
    public Result<Boolean> logout() {
        StpUtil.logout();
        return Result.success(true);
    }

    @PostMapping("/updateKeys")
    public Result<Void> updateKeys(@RequestBody UpdateKeysRequest request) {
        try {
            Long loginUserId = SecurityContext.getCurrentUserId();
            userAppService.updateKeys(loginUserId, request.getApiKey(), request.getBaseUrl());
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/updateProfile")
    public Result<UserInfo> updateProfile(@RequestBody UpdateProfileRequest request) {
        try {
            Long loginUserId = SecurityContext.getCurrentUserId();
            UserInfo user = userAppService.updateProfile(loginUserId, request.getUsername(), request.getEmail());
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/updatePassword")
    public Result<Boolean> updatePassword(@RequestBody UpdatePasswordRequest request) {
        try {
            Long loginUserId = SecurityContext.getCurrentUserId();
            userAppService.updatePassword(loginUserId, request.getOldPassword(), request.getNewPassword());
            StpUtil.logout(); // Force logout after password change
            return Result.success(true);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/uploadAvatar")
    public Result<UserInfo> uploadAvatar(@RequestParam("file") MultipartFile file) {
        try {
            Long loginUserId = SecurityContext.getCurrentUserId();
            UserInfo user = userAppService.updateAvatar(loginUserId, file);
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/cancelAccount")
    public Result<Boolean> cancelAccount() {
        try {
            Long loginUserId = SecurityContext.getCurrentUserId();
            userAppService.cancelAccount(loginUserId);
            StpUtil.logout();
            return Result.success(true);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @GetMapping("/info")
    public Result<UserInfo> getUserInfo() {
        try {
            Long loginUserId = SecurityContext.getCurrentUserId();
            UserInfo user = userAppService.getUserById(loginUserId);
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }
}