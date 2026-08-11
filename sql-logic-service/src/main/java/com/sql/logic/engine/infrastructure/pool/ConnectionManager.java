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

        String dbName = conf.getDbName();
        if (dbName != null && !dbName.isBlank()) {
            config.setCatalog(dbName);
        }

        return new HikariDataSource(config);
    }

    private String buildJdbcUrl(DbConnectionConf conf) {
        String dbType = conf.getDbType() != null ? conf.getDbType().toLowerCase() : "mysql";
        String dbName = conf.getDbName();
        switch (dbType) {
            case "mysql":
                String mysqlBase = String.format("jdbc:mysql://%s:%d", conf.getHost(), conf.getPort());
                return dbName != null && !dbName.isBlank()
                        ? mysqlBase + "/" + dbName + "?useSSL=false&serverTimezone=UTC"
                        : mysqlBase + "?useSSL=false&serverTimezone=UTC";
            case "postgresql":
                String pgBase = String.format("jdbc:postgresql://%s:%d", conf.getHost(), conf.getPort());
                return dbName != null && !dbName.isBlank()
                        ? pgBase + "/" + dbName
                        : pgBase;
            case "duckdb":
                // DuckDB can be :memory: or a file path stored in dbName or filePath
                String duckPath = resolveFilePath(conf);
                return "jdbc:duckdb:" + (duckPath != null ? duckPath : ":memory:");
            case "sqlite":
                // SQLite requires a file path
                String sqlitePath = resolveFilePath(conf);
                if (sqlitePath == null || sqlitePath.isBlank()) {
                    throw new IllegalArgumentException("SQLite requires a file path in dbName or filePath");
                }
                return "jdbc:sqlite:" + sqlitePath;
            case "csv":
                // CSV uses DuckDB backend; open in-memory and register CSV files as views
                return "jdbc:duckdb::memory:";
            default:
                throw new IllegalArgumentException("Unsupported database type: " + conf.getDbType());
        }
    }

    /**
     * Resolve file path from dbName or filePath column.
     * For file-based databases (DuckDB, SQLite), the file path comes from
     * either the dbName field (legacy) or filePath field (new).
     */
    private String resolveFilePath(DbConnectionConf conf) {
        String filePath = conf.getFilePath();
        if (filePath != null && !filePath.isBlank()) return filePath;
        String dbName = conf.getDbName();
        if (dbName != null && !dbName.isBlank() && !":memory:".equalsIgnoreCase(dbName)) return dbName;
        return null;
    }
}
