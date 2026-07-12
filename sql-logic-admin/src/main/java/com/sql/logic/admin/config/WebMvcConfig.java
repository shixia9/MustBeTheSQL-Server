package com.sql.logic.admin.config;

import com.sql.logic.admin.interceptor.AdminGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminGuard adminGuard;

    public WebMvcConfig(AdminGuard adminGuard) {
        this.adminGuard = adminGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminGuard)
                .addPathPatterns("/api/v1/admin/**")
                .excludePathPatterns("/api/v1/admin/check");
    }
}
