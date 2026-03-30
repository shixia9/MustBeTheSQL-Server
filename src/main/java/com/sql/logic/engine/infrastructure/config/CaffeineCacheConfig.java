package com.sql.logic.engine.infrastructure.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Global Caffeine Cache Configuration
 */
@Configuration
public class CaffeineCacheConfig {
    
    /**
     * General Single-level Cache: key-value
     */
    @Bean
    public Cache<String, Object> generalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(24, TimeUnit.HOURS)  // Expires 24 hours after writing
                .build();
    }

    /**
     * DDL Dedicated Secondary Cache: connectionId -> (tableName -> DDL)
     */
    @Bean
    public Cache<Long, Cache<String, String>> ddlCache() {
        return Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(30, TimeUnit.MINUTES)  // Expires 30 minutes after writing
                .build();
    }

}
