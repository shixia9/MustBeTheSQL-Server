package com.sql.logic.admin.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.admin.service.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminGuard implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);
    private final AdminUserService adminUserService;

    public AdminGuard(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
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
        if (!adminUserService.isSystemAdmin(userId)) {
            log.warn("[AdminGuard] Access denied for userId={} to {}", userId, request.getRequestURI());
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"Admin access required\"}");
            return false;
        }
        return true;
    }
}
