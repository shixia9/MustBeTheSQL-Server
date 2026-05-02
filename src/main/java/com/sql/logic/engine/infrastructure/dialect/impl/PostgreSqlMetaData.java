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

@Component
public class PostgreSqlMetaData implements MetaData {

    @Override
    public String dbType() {
        return "postgresql";
    }

    @Override
    public List<SchemaDTO> schemas(Connection connection) {
        List<SchemaDTO> schemas = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast')";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                SchemaDTO schema = new SchemaDTO();
                schema.setName(rs.getString("schema_name"));
                schemas.add(schema);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch PostgreSQL schemas", e);
        }
        return schemas;
    }

    @Override
    public List<TableDTO> tables(Connection connection, String schemaName) {
        List<TableDTO> tables = new ArrayList<>();
        String targetSchema = schemaName != null ? schemaName : "public";
        String sql = "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetSchema);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableDTO table = new TableDTO();
                    table.setName(rs.getString("table_name"));
                    String type = rs.getString("table_type");
                    table.setType("VIEW".equalsIgnoreCase(type) ? "VIEW" : "TABLE");
                    table.setComment(""); // PG comments need pg_description, skipping for brevity
                    tables.add(table);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch PostgreSQL tables", e);
        }
        return tables;
    }

    @Override
    public List<ColumnDTO> columns(Connection connection, String schemaName, String tableName) {
        List<ColumnDTO> columns = new ArrayList<>();
        String targetSchema = schemaName != null ? schemaName : "public";
        String sql = "SELECT column_name, data_type, is_nullable, column_default " +
                     "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetSchema);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnDTO col = new ColumnDTO();
                    col.setName(rs.getString("column_name"));
                    col.setDataType(rs.getString("data_type"));
                    col.setColumnType(rs.getString("data_type"));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    col.setDefaultValue(rs.getString("column_default"));
                    // Auto-increment logic
                    col.setAutoIncrement(col.getDefaultValue() != null && col.getDefaultValue().contains("nextval"));
                    col.setPrimaryKey(false); // Skipping precise PK check for brevity in PG, can be fetched via pg_constraint
                    col.setComment("");
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch PostgreSQL columns", e);
        }
        return columns;
    }

    @Override
    public List<IndexDTO> indexes(Connection connection, String schemaName, String tableName) {
        Map<String, IndexDTO> indexMap = new HashMap<>();
        String sql = "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName != null ? schemaName : "public");
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    IndexDTO idx = new IndexDTO();
                    idx.setName(rs.getString("indexname"));
                    String def = rs.getString("indexdef");
                    idx.setType(def != null && def.contains("UNIQUE") ? "UNIQUE" : "NORMAL");
                    idx.setColumns(new ArrayList<>()); // Requires parsing indexdef
                    idx.setComment("");
                    indexMap.put(idx.getName(), idx);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch PostgreSQL indexes", e);
        }
        return new ArrayList<>(indexMap.values());
    }

    @Override
    public String tableDDL(Connection connection, String schemaName, String tableName) {
        // PostgreSQL DDL generation is complex. A basic fallback using JDBC MetaData is typical, or pg_dump
        // For simplicity, we just return a placeholder or very basic CREATE TABLE
        return "-- DDL extraction for PostgreSQL requires pg_dump or complex pg_catalog queries.\n" +
               "-- Please refer to actual database client for full DDL.\n" +
               "CREATE TABLE " + tableName + " (...);";
    }
}
