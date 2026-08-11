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
import java.util.List;

/**
 * CSV/Excel metadata provider backed by DuckDB.
 * Uses DuckDB's read_csv_auto() and read_xlsx() functions to create virtual views
 * over CSV/Excel files. The actual table/column introspection is delegated to DuckDB.
 */
@Component
public class CsvMetaData implements MetaData {

    @Override
    public String dbType() {
        return "csv";
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
        // Query DuckDB for registered CSV views and tables
        String sql = "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = 'main'";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                TableDTO table = new TableDTO();
                table.setName(rs.getString("table_name"));
                String type = rs.getString("table_type");
                table.setType("VIEW".equalsIgnoreCase(type) ? "VIEW" : "TABLE");
                table.setComment("");
                tables.add(table);
            }
        } catch (SQLException e) {
            // Fallback: use DuckDB's show tables
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    TableDTO table = new TableDTO();
                    table.setName(rs.getString(1));
                    table.setType("TABLE");
                    table.setComment("");
                    tables.add(table);
                }
            } catch (SQLException ex) {
                List<TableDTO> empty = new ArrayList<>();
                return empty;
            }
        }
        return tables;
    }

    @Override
    public List<ColumnDTO> columns(Connection connection, String schemaName, String tableName) {
        List<ColumnDTO> columns = new ArrayList<>();
        String sql = "SELECT column_name, data_type, is_nullable FROM information_schema.columns " +
                     "WHERE table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnDTO col = new ColumnDTO();
                    col.setName(rs.getString("column_name"));
                    col.setDataType(rs.getString("data_type"));
                    col.setColumnType(rs.getString("data_type"));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    col.setPrimaryKey(false);
                    col.setAutoIncrement(false);
                    col.setDefaultValue(null);
                    col.setComment("");
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            // Fallback: use DESCRIBE
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("DESCRIBE \"" + tableName + "\"")) {
                while (rs.next()) {
                    ColumnDTO col = new ColumnDTO();
                    col.setName(rs.getString(1));
                    col.setDataType(rs.getString(2));
                    col.setColumnType(rs.getString(2));
                    col.setNullable(rs.getString(3) == null || "YES".equalsIgnoreCase(rs.getString(3)));
                    col.setPrimaryKey(false);
                    col.setAutoIncrement(false);
                    col.setDefaultValue(null);
                    col.setComment("");
                    columns.add(col);
                }
            } catch (SQLException ex) {
                return columns;
            }
        }
        return columns;
    }

    @Override
    public List<IndexDTO> indexes(Connection connection, String schemaName, String tableName) {
        return new ArrayList<>();
    }

    @Override
    public String tableDDL(Connection connection, String schemaName, String tableName) {
        StringBuilder sb = new StringBuilder("-- CSV virtual table: ").append(tableName).append("\n");
        List<ColumnDTO> cols = columns(connection, schemaName, tableName);
        if (cols.isEmpty()) {
            sb.append("-- (columns auto-detected from CSV header)\n");
        } else {
            sb.append("CREATE TABLE ").append(tableName).append(" (\n");
            for (int i = 0; i < cols.size(); i++) {
                ColumnDTO col = cols.get(i);
                sb.append("  ").append(col.getName()).append(" ").append(col.getDataType());
                if (i < cols.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append(");");
        }
        return sb.toString();
    }
}
