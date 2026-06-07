package com.sql.logic.engine.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class SaTokenConfig {
    
    @Bean
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**") // 拦截所有路径
                .addExclude("/favicon.ico") // 放行 favicon.ico
                .setAuth(obj -> {
                    SaRouter.match("/**")
                            .notMatch("/api/v1/user/**")
                            .check(r -> StpUtil.checkLogin());
                });
    }

    public SaResult getSaResult(Throwable throwable) {
                switch (throwable) {
            case NotLoginException notLoginException:
                log.error("please login first: {}", notLoginException.getMessage());
                return SaResult.error("please login first");
            default:
                return SaResult.error(throwable.getMessage());
        }
    }

}
