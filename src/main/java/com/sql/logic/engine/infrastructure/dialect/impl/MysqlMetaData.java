package com.sql.logic.engine.infrastructure.dialect.impl;

import com.sql.logic.engine.infrastructure.dialect.MetaData;
import com.sql.logic.engine.infrastructure.dialect.model.*;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class MysqlMetaData implements MetaData {

    /**
     * SQL identifier whitelist pattern: only letters, digits, and underscores.
     * Prevents SQL injection in DDL statements where parameterized queries are not supported.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    @Override
    public String dbType() {
        return "mysql";
    }

    /**
     * Validate a SQL identifier to prevent injection in SHOW CREATE TABLE and similar
     * statements that don't support parameterized queries.
     */
    private void validateIdentifier(String identifier, String label) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be null or empty");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": '" + identifier +
                    "'. Only alphanumeric characters and underscores are allowed.");
        }
    }

    @Override
    public List<SchemaDTO> schemas(Connection connection) {
        List<SchemaDTO> schemas = new ArrayList<>();
        String sql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                SchemaDTO schema = new SchemaDTO();
                schema.setName(rs.getString("SCHEMA_NAME"));
                schemas.add(schema);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch MySQL schemas", e);
        }
        return schemas;
    }

    @Override
    public List<TableDTO> tables(Connection connection, String schemaName) {
        List<TableDTO> tables = new ArrayList<>();
        String sql = "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName != null ? schemaName : connection.getCatalog());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableDTO table = new TableDTO();
                    table.setName(rs.getString("TABLE_NAME"));
                    String type = rs.getString("TABLE_TYPE");
                    table.setType("VIEW".equalsIgnoreCase(type) ? "VIEW" : "TABLE");
                    table.setComment(rs.getString("TABLE_COMMENT"));
                    tables.add(table);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch MySQL tables", e);
        }
        return tables;
    }

    @Override
    public List<ColumnDTO> columns(Connection connection, String schemaName, String tableName) {
        List<ColumnDTO> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, EXTRA, COLUMN_DEFAULT, COLUMN_COMMENT " +
                     "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName != null ? schemaName : connection.getCatalog());
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnDTO col = new ColumnDTO();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setDataType(rs.getString("DATA_TYPE"));
                    col.setColumnType(rs.getString("COLUMN_TYPE"));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    String key = rs.getString("COLUMN_KEY");
                    col.setPrimaryKey("PRI".equalsIgnoreCase(key));
                    String extra = rs.getString("EXTRA");
                    col.setAutoIncrement(extra != null && extra.contains("auto_increment"));
                    col.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    col.setComment(rs.getString("COLUMN_COMMENT"));
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch MySQL columns", e);
        }
        return columns;
    }

    @Override
    public List<IndexDTO> indexes(Connection connection, String schemaName, String tableName) {
        Map<String, IndexDTO> indexMap = new HashMap<>();
        String sql = "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, INDEX_COMMENT " +
                     "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY SEQ_IN_INDEX";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName != null ? schemaName : connection.getCatalog());
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String idxName = rs.getString("INDEX_NAME");
                    IndexDTO idx = indexMap.computeIfAbsent(idxName, k -> {
                        IndexDTO newIdx = new IndexDTO();
                        newIdx.setName(idxName);
                        try {
                            newIdx.setType("PRIMARY".equals(idxName) ? "PRIMARY" :
                                         (rs.getInt("NON_UNIQUE") == 0 ? "UNIQUE" : "NORMAL"));
                            newIdx.setComment(rs.getString("INDEX_COMMENT"));
                        } catch (SQLException ignored) {}
                        newIdx.setColumns(new ArrayList<>());
                        return newIdx;
                    });
                    idx.getColumns().add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch MySQL indexes", e);
        }
        return new ArrayList<>(indexMap.values());
    }

    @Override
    public String tableDDL(Connection connection, String schemaName, String tableName) {
        // Validate identifiers to prevent SQL injection in SHOW CREATE TABLE
        validateIdentifier(tableName, "table name");
        try {
            String targetSchema = schemaName != null ? schemaName : connection.getCatalog();
            if (targetSchema != null && !targetSchema.isEmpty()) {
                validateIdentifier(targetSchema, "schema name");
            }
            // Use Statement since SHOW CREATE TABLE doesn't support PreparedStatement parameters,
            // but identifiers are validated against a strict whitelist pattern above.
            String sql = "SHOW CREATE TABLE `" + targetSchema + "`.`" + tableName + "`";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getString(2);
                }
            } catch (SQLException e) {
                // It might be a view, try SHOW CREATE VIEW
                String viewSql = "SHOW CREATE VIEW `" + targetSchema + "`.`" + tableName + "`";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(viewSql)) {
                    if (rs.next()) {
                        return rs.getString(2);
                    }
                } catch (SQLException ignored) {}
                throw new RuntimeException("Failed to fetch MySQL DDL for " + tableName, e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch MySQL DDL for " + tableName, e);
        }
        return null;
    }
}