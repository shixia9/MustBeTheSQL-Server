package com.sql.logic.engine.infrastructure.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.AdminUserAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor that guards /api/v1/admin/** endpoints.
 * Rejects requests from non-admin users with 403 before they reach the controller.
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);

    private final AdminUserAppService adminUserAppService;

    public AdminGuard(AdminUserAppService adminUserAppService) {
        this.adminUserAppService = adminUserAppService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!StpUtil.isLogin()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Not logged in\"}");
            return false;
        }
        String idStr = (String) StpUtil.getLoginId();
        Long userId = Long.valueOf(idStr);
        if (!adminUserAppService.isSystemAdmin(userId)) {
            log.warn("[AdminGuard] Access denied for userId={} to {}", userId, request.getRequestURI());
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"Admin access required\"}");
            return false;
        }
        return true;
    }
}
