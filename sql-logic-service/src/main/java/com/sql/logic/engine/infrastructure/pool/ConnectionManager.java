package com.sql.logic.engine.infrastructure.pool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ConnectionManager {

    private final Cache<Long, HikariDataSource> dataSourceCache;

    public ConnectionManager() {
        this.dataSourceCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterAccess(30, TimeUnit.MINUTES) // release connections if idle for 30 min
                .removalListener((RemovalListener<Long, HikariDataSource>) (key, value, cause) -> {
                    if (value != null && !value.isClosed()) {
                        log.info("Closing DataSource for connectionId: {} due to {}", key, cause);
                        value.close();
                    }
                })
                .build();
    }

    public Connection getConnection(DbConnectionConf conf) throws SQLException {
        HikariDataSource dataSource = dataSourceCache.get(conf.getId(), k -> createDataSource(conf));
        return dataSource.getConnection();
    }

    public void releaseConnection(Long connectionId) {
        dataSourceCache.invalidate(connectionId);
    }

    /**
     * Lightweight connection test — NO Hikari pool created.
     * Uses {@link java.sql.DriverManager} directly so the test has zero side effects:
     * no cached DataSource, no leftover pool threads.
     */
    public boolean testConnection(DbConnectionConf conf) {
        String url = buildJdbcUrl(conf);
        try (Connection conn = DriverManager.getConnection(url, conf.getUsername(), conf.getPassword())) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.warn("Connection test failed for {}:{}: {}",
                    conf.getHost(), conf.getPort(), e.getMessage());
            return false;
        }
    }

    /**
     * Pre-warm a connection pool by creating a test connection and immediately releasing it.
     * This eliminates cold-start latency on the first query for a given database configuration.
     */
    public void warmup(DbConnectionConf conf) {
        try {
            Connection conn = getConnection(conf);
            conn.isValid(5); // Quick validation
            conn.close();
            log.info("Successfully warmed up connection pool for connectionId: {}", conf.getId());
        } catch (SQLException e) {
            log.warn("Failed to warm up connection pool for connectionId: {}: {}", conf.getId(), e.getMessage());
        }
    }

    private HikariDataSource createDataSource(DbConnectionConf conf) {
        log.info("Creating new HikariDataSource for connectionId: {}", conf.getId());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl(conf));
        config.setUsername(conf.getUsername());
        config.setPassword(conf.getPassword());
        
        // Pool configuration
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setIdleTimeout(600000); // 10 minutes
        config.setConnectionTimeout(10000); // 10 seconds
        config.setPoolName("HikariPool-UserConn-" + conf.getId());
        
        return new HikariDataSource(config);
    }

    private String buildJdbcUrl(DbConnectionConf conf) {
        if ("mysql".equalsIgnoreCase(conf.getDbType())) {
            return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC", 
                    conf.getHost(), conf.getPort(), conf.getDbName());
        } else if ("postgresql".equalsIgnoreCase(conf.getDbType())) {
            return String.format("jdbc:postgresql://%s:%d/%s", 
                    conf.getHost(), conf.getPort(), conf.getDbName());
        }
        throw new IllegalArgumentException("Unsupported database type: " + conf.getDbType());
    }
}
