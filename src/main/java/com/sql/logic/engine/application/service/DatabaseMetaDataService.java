package com.sql.logic.engine.application.service;

import org.springframework.stereotype.Service;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class DatabaseMetaDataService {

    private final DatabaseAppService databaseAppService;
    // Cache: connectionId -> (tableName -> DDL)
    @Resource
    private Cache<Long, Cache<String, String>> ddlCache;

    public DatabaseMetaDataService(DatabaseAppService databaseAppService) {
        this.databaseAppService = databaseAppService;
    }

    public List<String> getTableNames(Long connectionId) {
        List<String> tableNames = new ArrayList<>();
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();
            
            try (ResultSet rs = metaData.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    tableNames.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch table names: " + e.getMessage(), e);
        }
        return tableNames;
    }

    public String getTableDDL(Long connectionId, String tableName) {
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
        ddl = fetchDDLFromDatabase(connectionId, tableName);
        if (ddl != null) {
            connCache.put(tableName, ddl);
        }
        return ddl;
    }
    
    public void clearCache(Long connectionId) {
        ddlCache.invalidate(connectionId);
    }

    private String fetchDDLFromDatabase(Long connectionId, String tableName) {
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            String dbType = conn.getMetaData().getDatabaseProductName().toLowerCase();
            
            try (Statement stmt = conn.createStatement()) {
                if (dbType.contains("mysql")) {
                    try (ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + tableName + "`")) {
                        if (rs.next()) {
                            return cleanDDL(rs.getString(2));
                        }
                    }
                } else {
                    // Fallback for PostgreSQL and others
                    return buildFallbackDDL(conn, tableName);
                }
            } catch (Exception e) {
                 return buildFallbackDDL(conn, tableName);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch DDL for table " + tableName, e);
        }
        return null;
    }
    
    private String buildFallbackDDL(Connection conn, String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (\n");
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(conn.getCatalog(), conn.getSchema(), tableName, "%")) {
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