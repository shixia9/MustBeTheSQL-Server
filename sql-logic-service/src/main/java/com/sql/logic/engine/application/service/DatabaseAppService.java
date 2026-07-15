package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.infrastructure.pool.ConnectionManager;

import jodd.util.StringUtil;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

@Service
public class DatabaseAppService {

    private final DbConnectionConfDao dbConnectionConfDao;
    private final ConnectionManager connectionManager;

    public DatabaseAppService(DbConnectionConfDao dbConnectionConfDao, ConnectionManager connectionManager) {
        this.dbConnectionConfDao = dbConnectionConfDao;
        this.connectionManager = connectionManager;
    }

    public List<DbConnectionConf> getUserConnections(Long userId) {
        QueryWrapper<DbConnectionConf> query = new QueryWrapper<>();
        query.eq("user_id", userId).or().eq("is_test", 1); // User's own DBs + shared test DBs
        List<DbConnectionConf> connections = dbConnectionConfDao.selectList(query);
        // Hide passwords
        for (DbConnectionConf conf : connections) {
            conf.setPassword(null);
        }
        return connections;
    }

    public DbConnectionConf addConnection(DbConnectionConf conf) {
        // Simple validation — dbName is optional (connect to server, browse all schemas)
        if (conf.getName() == null || conf.getHost() == null || conf.getPort() == null ||
            conf.getUsername() == null || conf.getPassword() == null) {
            throw new IllegalArgumentException("Missing required connection parameters");
        }
        conf.setIsTest(0); // Users can only add their own non-test connections
        dbConnectionConfDao.insert(conf);
        // Pre-warm the connection pool to eliminate cold-start latency
        connectionManager.warmup(conf);
        conf.setPassword(null);
        return conf;
    }

    public DbConnectionConf updateConnection(Long userId, DbConnectionConf conf) {
        DbConnectionConf existing = dbConnectionConfDao.selectById(conf.getId());
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Connection not found or permission denied");
        }
        if (StringUtil.isEmpty(conf.getPassword()) && conf.getId() != null) {
            conf.setPassword(existing.getPassword());
            conf.setDbName(existing.getDbName()); // keep old password if not updated
        }
        dbConnectionConfDao.updateById(conf);
        // Invalidate cached DataSource so a fresh pool is created with updated config
        connectionManager.releaseConnection(conf.getId());
        conf.setPassword(null);
        return conf;
    }

    public void deleteConnection(Long userId, Long connectionId) {
        DbConnectionConf existing = dbConnectionConfDao.selectById(connectionId);
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Connection not found or permission denied");
        }
        if (existing.getIsTest() == 1) {
            throw new IllegalArgumentException("Cannot delete shared test connection");
        }
        dbConnectionConfDao.deleteById(connectionId);
        // Close and remove the connection pool
        connectionManager.releaseConnection(connectionId);
    }

    public boolean testConnection(DbConnectionConf conf) {
        // If testing an existing connection without providing password again
        if (StringUtil.isEmpty(conf.getPassword()) && conf.getId() != null) {
            DbConnectionConf existing = dbConnectionConfDao.selectById(conf.getId());
            if (existing != null) {
                conf.setPassword(existing.getPassword());
            conf.setDbName(existing.getDbName());
            }
        }
        return connectionManager.testConnection(conf);
    }

    public Connection getConnection(Long connectionId) throws SQLException {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) {
            throw new IllegalArgumentException("Database connection not found");
        }
        return connectionManager.getConnection(conf);
    }

    public void assertUserCanAccessConnection(Long userId, Long connectionId) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) {
            throw new IllegalArgumentException("Database connection not found");
        }
        boolean ownedByUser = conf.getUserId() != null && conf.getUserId().equals(userId);
        boolean isSharedTest = conf.getIsTest() != null && conf.getIsTest() == 1;
        if (!ownedByUser && !isSharedTest) {
            throw new IllegalArgumentException("Connection not found or permission denied");
        }
    }

    public Connection getConnectionForUser(Long userId, Long connectionId) throws SQLException {
        assertUserCanAccessConnection(userId, connectionId);
        return getConnection(connectionId);
    }

    /**
     * Get the database type (e.g., "mysql", "postgresql") for a connection.
     * Used by agent nodes to determine schema context dialect.
     */
    public String getConnectionDbType(Long connectionId) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        return conf != null ? conf.getDbType() : null;
    }

    /**
     * Retrieve the full connection config (password excluded) for a connection.
     * Used by agent execution services to resolve default database name.
     */
    public DbConnectionConf getConnectionConfig(Long connectionId) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf != null) conf.setPassword(null);
        return conf;
    }
}
