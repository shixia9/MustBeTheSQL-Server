package com.sql.logic.engine.infrastructure.dialect.impl;

import com.sql.logic.engine.infrastructure.dialect.MetaData;
import com.sql.logic.engine.infrastructure.dialect.model.*;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SqliteMetaData implements MetaData {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

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
    public String dbType() {
        return "sqlite";
    }

    @Override
    public List<SchemaDTO> schemas(Connection connection) {
        List<SchemaDTO> schemas = new ArrayList<>();
        SchemaDTO main = new SchemaDTO();
        main.setName("main");
        schemas.add(main);
        return schemas;
    }

    @Override
    public List<TableDTO> tables(Connection connection, String schemaName) {
        List<TableDTO> tables = new ArrayList<>();
        String sql = "SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%'";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                TableDTO table = new TableDTO();
                table.setName(rs.getString("name"));
                table.setType("view".equalsIgnoreCase(rs.getString("type")) ? "VIEW" : "TABLE");
                table.setComment("");
                tables.add(table);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch SQLite tables", e);
        }
        return tables;
    }

    @Override
    public List<ColumnDTO> columns(Connection connection, String schemaName, String tableName) {
        validateIdentifier(tableName, "table name");
        List<ColumnDTO> columns = new ArrayList<>();
        List<String> pkColumns = getPkColumns(connection, tableName);
        String sql = "PRAGMA table_info('" + tableName + "')";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ColumnDTO col = new ColumnDTO();
                col.setName(rs.getString("name"));
                col.setDataType(rs.getString("type"));
                col.setColumnType(rs.getString("type"));
                int notNull = rs.getInt("notnull");
                col.setNullable(notNull == 0);
                col.setDefaultValue(rs.getString("dflt_value"));
                int pkOrder = rs.getInt("pk");
                col.setPrimaryKey(pkOrder > 0 || pkColumns.contains(col.getName()));
                col.setAutoIncrement(pkOrder > 0);
                col.setComment("");
                columns.add(col);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch SQLite columns for " + tableName, e);
        }
        return columns;
    }

    private List<String> getPkColumns(Connection connection, String tableName) {
        List<String> pks = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement("PRAGMA table_info('" + tableName + "')");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    pks.add(rs.getString("name"));
                }
            }
        } catch (SQLException ignored) {}
        return pks;
    }

    @Override
    public List<IndexDTO> indexes(Connection connection, String schemaName, String tableName) {
        validateIdentifier(tableName, "table name");
        Map<String, IndexDTO> indexMap = new HashMap<>();
        String sql = "PRAGMA index_list('" + tableName + "')";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                IndexDTO idx = new IndexDTO();
                idx.setName(rs.getString("name"));
                int unique = rs.getInt("unique");
                idx.setType(unique == 1 ? "UNIQUE" : "NORMAL");
                idx.setColumns(new ArrayList<>());
                idx.setComment(rs.getString("origin"));
                indexMap.put(idx.getName(), idx);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch SQLite indexes for " + tableName, e);
        }
        // Fetch column names for each index
        for (IndexDTO idx : indexMap.values()) {
            String infoSql = "PRAGMA index_info('" + idx.getName() + "')";
            try (PreparedStatement stmt = connection.prepareStatement(infoSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    idx.getColumns().add(rs.getString("name"));
                }
            } catch (SQLException ignored) {}
        }
        return new ArrayList<>(indexMap.values());
    }

    @Override
    public String tableDDL(Connection connection, String schemaName, String tableName) {
        validateIdentifier(tableName, "table name");
        String sql = "SELECT sql FROM sqlite_master WHERE name = ? AND type = 'table'";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String ddl = rs.getString("sql");
                    return ddl != null ? ddl : "";
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch SQLite DDL for " + tableName, e);
        }
        return "";
    }
}
