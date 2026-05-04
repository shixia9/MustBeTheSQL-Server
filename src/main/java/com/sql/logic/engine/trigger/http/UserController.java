package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.trigger.http.dto.LoginRequest;
import com.sql.logic.engine.trigger.http.dto.RegisterRequest;
import com.sql.logic.engine.trigger.http.dto.UpdatePasswordRequest;
import com.sql.logic.engine.trigger.http.dto.UpdateProfileRequest;
import com.sql.logic.engine.trigger.http.response.Result;

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
    public Result<Void> updateKeys(@RequestParam Long userId, @RequestParam(required = false) String apiKey, @RequestParam(required = false) String secretKey) {
        try {
            userAppService.updateKeys(userId, apiKey, secretKey);
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/updateProfile")
    public Result<UserInfo> updateProfile(@RequestBody UpdateProfileRequest request) {
        try {
            UserInfo user = userAppService.updateProfile(request.getUserId(), request.getUsername(), request.getEmail());
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/updatePassword")
    public Result<Boolean> updatePassword(@RequestBody UpdatePasswordRequest request) {
        try {
            userAppService.updatePassword(request.getUserId(), request.getOldPassword(), request.getNewPassword());
            StpUtil.logout(); // Force logout after password change
            return Result.success(true);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/uploadAvatar")
    public Result<UserInfo> uploadAvatar(@RequestParam("userId") Long userId, @RequestParam("file") MultipartFile file) {
        try {
            UserInfo user = userAppService.updateAvatar(userId, file);
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/cancelAccount")
    public Result<Boolean> cancelAccount(@RequestParam("userId") Long userId) {
        try {
            userAppService.cancelAccount(userId);
            StpUtil.logout();
            return Result.success(true);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }

    @GetMapping("/info")
    public Result<UserInfo> getUserInfo(@RequestParam("userId") Long userId) {
        try {
            UserInfo user = userAppService.getUserById(userId);
            user.setPassword(null);
            return Result.success(user);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }
}
