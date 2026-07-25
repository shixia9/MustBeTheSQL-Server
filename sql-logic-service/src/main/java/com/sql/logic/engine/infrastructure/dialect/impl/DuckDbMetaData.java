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
public class DuckDbMetaData implements MetaData {

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
        return "duckdb";
    }

    @Override
    public List<SchemaDTO> schemas(Connection connection) {
        List<SchemaDTO> schemas = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.schemata";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                SchemaDTO schema = new SchemaDTO();
                schema.setName(rs.getString("schema_name"));
                schemas.add(schema);
            }
        } catch (SQLException e) {
            SchemaDTO main = new SchemaDTO();
            main.setName("main");
            schemas.add(main);
        }
        return schemas;
    }

    @Override
    public List<TableDTO> tables(Connection connection, String schemaName) {
        List<TableDTO> tables = new ArrayList<>();
        String sql = "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName != null ? schemaName : "main");
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableDTO table = new TableDTO();
                    table.setName(rs.getString("table_name"));
                    String type = rs.getString("table_type");
                    table.setType("VIEW".equalsIgnoreCase(type) ? "VIEW" : "TABLE");
                    table.setComment("");
                    tables.add(table);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch DuckDB tables", e);
        }
        return tables;
    }

    @Override
    public List<ColumnDTO> columns(Connection connection, String schemaName, String tableName) {
        List<ColumnDTO> columns = new ArrayList<>();
        String sql = "SELECT column_name, data_type, is_nullable, column_default " +
                     "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName != null ? schemaName : "main");
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnDTO col = new ColumnDTO();
                    col.setName(rs.getString("column_name"));
                    col.setDataType(rs.getString("data_type"));
                    col.setColumnType(rs.getString("data_type"));
                    String nullable = rs.getString("is_nullable");
                    col.setNullable("YES".equalsIgnoreCase(nullable));
                    col.setDefaultValue(rs.getString("column_default"));
                    col.setAutoIncrement(false);
                    col.setPrimaryKey(false);
                    col.setComment("");
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch DuckDB columns for " + tableName, e);
        }
        // Detect primary keys from constraints
        try {
            String pkSql = "SELECT kcu.column_name FROM information_schema.table_constraints tc " +
                           "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
                           "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = ?";
            try (PreparedStatement stmt = connection.prepareStatement(pkSql)) {
                stmt.setString(1, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String pkCol = rs.getString("column_name");
                        columns.stream().filter(c -> pkCol.equals(c.getName()))
                                .forEach(c -> c.setPrimaryKey(true));
                    }
                }
            }
        } catch (SQLException ignored) {}
        return columns;
    }

    @Override
    public List<IndexDTO> indexes(Connection connection, String schemaName, String tableName) {
        Map<String, IndexDTO> indexMap = new HashMap<>();
        // DuckDB currently has limited index introspection; return what we can
        return new ArrayList<>(indexMap.values());
    }

    @Override
    public String tableDDL(Connection connection, String schemaName, String tableName) {
        validateIdentifier(tableName, "table name");
        // DuckDB doesn't support SHOW CREATE TABLE; use PRAGMA or build from columns
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (\n");
        List<ColumnDTO> cols = columns(connection, schemaName, tableName);
        for (int i = 0; i < cols.size(); i++) {
            ColumnDTO col = cols.get(i);
            sb.append("  ").append(col.getName()).append(" ").append(col.getDataType());
            if (!col.getNullable()) sb.append(" NOT NULL");
            if (col.getDefaultValue() != null) sb.append(" DEFAULT ").append(col.getDefaultValue());
            if (i < cols.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(");");
        return sb.toString();
    }
}
