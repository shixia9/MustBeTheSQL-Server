package com.sql.logic.engine.application.service;

import com.sql.logic.engine.infrastructure.dialect.DialectFactory;
import com.sql.logic.engine.infrastructure.dialect.MetaData;
import com.sql.logic.engine.infrastructure.dialect.model.ColumnDTO;
import com.sql.logic.engine.infrastructure.dialect.model.SchemaDTO;
import com.sql.logic.engine.infrastructure.dialect.model.TableDTO;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import org.springframework.stereotype.Service;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DatabaseMetaDataService {

    private final DatabaseAppService databaseAppService;
    private final DialectFactory dialectFactory;
    private final DbConnectionConfDao dbConnectionConfDao;
    
    // Cache: connectionId -> (tableName -> DDL)
    @Resource
    private Cache<Long, Cache<String, String>> ddlCache;

    public DatabaseMetaDataService(DatabaseAppService databaseAppService, DialectFactory dialectFactory, DbConnectionConfDao dbConnectionConfDao) {
        this.databaseAppService = databaseAppService;
        this.dialectFactory = dialectFactory;
        this.dbConnectionConfDao = dbConnectionConfDao;
    }

    public List<String> getTableNames(Long connectionId) {
        return getTableNames(connectionId, null);
    }

    /**
     * List available database/schema names for the given connection.
     */
    public List<String> getSchemas(Long connectionId) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) throw new IllegalArgumentException("Connection not found");
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            MetaData metaData = dialectFactory.getMetaData(conf.getDbType());
            return metaData.schemas(conn).stream().map(SchemaDTO::getName).collect(Collectors.toList());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch schemas: " + e.getMessage(), e);
        }
    }

    public List<String> getTableNames(Long connectionId, String schemaName) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) throw new IllegalArgumentException("Connection not found");
        
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            MetaData metaData = dialectFactory.getMetaData(conf.getDbType());
            return metaData.tables(conn, schemaName).stream().map(TableDTO::getName).collect(Collectors.toList());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch table names: " + e.getMessage(), e);
        }
    }

    public String getTableDDL(Long connectionId, String tableName) {
        return getTableDDL(connectionId, null, tableName);
    }

    public String getTableDDL(Long connectionId, String schemaName, String tableName) {
        // Check cache first
        Cache<String, String> connCache = ddlCache.get(connectionId, key -> 
            Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build()
        );
        String ddl = connCache.getIfPresent(tableName);
        if (ddl != null) {
            return ddl;
        }
        ddl = fetchDDLFromDatabase(connectionId, schemaName, tableName);
        if (ddl != null) {
            connCache.put(tableName, ddl);
        }
        return ddl;
    }
    
    public void clearCache(Long connectionId) {
        ddlCache.invalidate(connectionId);
    }

    private String fetchDDLFromDatabase(Long connectionId, String schemaName, String tableName) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) return null;
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            MetaData metaData = dialectFactory.getMetaData(conf.getDbType());
            String ddl = metaData.tableDDL(conn, schemaName, tableName);
            if (ddl != null && !ddl.trim().isEmpty()) {
                return cleanDDL(ddl);
            }
            return buildFallbackDDL(conn, schemaName, tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch DDL for table " + tableName, e);
        }
    }
    
    private String buildFallbackDDL(Connection conn, String schemaName, String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (\n");
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(schemaName != null ? schemaName : conn.getCatalog(), conn.getSchema(), tableName, "%")) {
            boolean hasColumns = false;
            while (rs.next()) {
                hasColumns = true;
                String colName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                String remarks = rs.getString("REMARKS");
                
                sb.append("  ").append(colName).append(" ").append(typeName);
                if (size > 0 && size < 10000) sb.append("(").append(size).append(")");
                if (remarks != null && !remarks.isEmpty()) {
                    sb.append(" COMMENT '").append(remarks).append("'");
                }
                sb.append(",\n");
            }
            if (!hasColumns) {
                return null;
            }
        }
        sb.append(");");
        return sb.toString();
    }
    
    /**
     * Get column metadata for a specific table.
     * Returns list of ColumnDTO with name, dataType, comment, primaryKey info.
     * Used by ColumnSampleService and SchemaLinkingNode for schema prompt rendering.
     */
    public List<ColumnDTO> getTableColumns(Long connectionId, String tableName) {
        return getTableColumns(connectionId, null, tableName);
    }

    public List<ColumnDTO> getTableColumns(Long connectionId, String schemaName, String tableName) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) throw new IllegalArgumentException("Connection not found");
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            MetaData metaData = dialectFactory.getMetaData(conf.getDbType());
            return metaData.columns(conn, schemaName, tableName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch columns for table " + tableName, e);
        }
    }

    private String cleanDDL(String rawDdl) {
        // Remove AUTO_INCREMENT, ENGINE, DEFAULT CHARSET etc. to save tokens
        return rawDdl.replaceAll("AUTO_INCREMENT=\\d+", "")
                     .replaceAll("ENGINE=\\w+", "")
                     .replaceAll("DEFAULT CHARSET=\\w+", "")
                     .replaceAll("COLLATE=\\w+", "")
                     .replaceAll("ROW_FORMAT=\\w+", "")
                     .trim();
    }
}