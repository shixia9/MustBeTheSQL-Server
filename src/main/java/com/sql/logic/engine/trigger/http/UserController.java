package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.trigger.http.dto.LoginRequest;
import com.sql.logic.engine.trigger.http.dto.RegisterRequest;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserAppService userAppService;

    public UserController(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    @PostMapping("/login")
    public Result<UserInfo> login(@RequestBody LoginRequest request) {
        try {
            UserInfo user = userAppService.login(request.getEmail(), request.getPassword());
            // Hide password in response
            user.setPassword(null);
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
