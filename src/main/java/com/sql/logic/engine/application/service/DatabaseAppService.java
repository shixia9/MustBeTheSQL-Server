package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.infrastructure.pool.ConnectionManager;
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
        // Simple validation
        if (conf.getName() == null || conf.getHost() == null || conf.getPort() == null ||
            conf.getUsername() == null || conf.getPassword() == null || conf.getDbName() == null) {
            throw new IllegalArgumentException("Missing required connection parameters");
        }
        conf.setIsTest(0); // Users can only add their own non-test connections
        dbConnectionConfDao.insert(conf);
        conf.setPassword(null);
        return conf;
    }

    public DbConnectionConf updateConnection(Long userId, DbConnectionConf conf) {
        DbConnectionConf existing = dbConnectionConfDao.selectById(conf.getId());
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Connection not found or permission denied");
        }
        if (conf.getPassword() == null || conf.getPassword().isEmpty()) {
            conf.setPassword(existing.getPassword()); // keep old password if not updated
        }
        dbConnectionConfDao.updateById(conf);
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
    }

    public boolean testConnection(DbConnectionConf conf) {
        // If testing an existing connection without providing password again
        if (conf.getPassword() == null && conf.getId() != null) {
            DbConnectionConf existing = dbConnectionConfDao.selectById(conf.getId());
            if (existing != null) {
                conf.setPassword(existing.getPassword());
            }
        }
        
        try (Connection conn = connectionManager.getConnection(conf)) {
            return conn.isValid(5); // 5 seconds timeout
        } catch (SQLException e) {
            throw new IllegalArgumentException("Connection failed: " + e.getMessage());
        }
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
}
