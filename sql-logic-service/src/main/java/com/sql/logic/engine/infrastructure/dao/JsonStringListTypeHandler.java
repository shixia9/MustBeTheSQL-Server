package com.sql.logic.engine.infrastructure.dao;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * Phase B — MyBatis TypeHandler for {@code List<String>} ↔ JSON column.
 * Used by {@code memory_item.tags} (and any future JSON string-list column).
 *
 * Null-safe: a NULL DB value yields an empty list on read; an empty list
 * is persisted as {@code []} (not NULL) to preserve index semantics.
 */
@MappedTypes(List.class)
public class JsonStringListTypeHandler extends BaseTypeHandler<List<String>> {

    private static final Logger log = LoggerFactory.getLogger(JsonStringListTypeHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    private String toJson(List<String> list) {
        if (list == null) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("[JsonStringListTypeHandler] serialize failed: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<String> result = MAPPER.readValue(json, TYPE);
            return result == null ? Collections.emptyList() : result;
        } catch (Exception e) {
            log.warn("[JsonStringListTypeHandler] deserialize failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, toJson(parameter));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return fromJson(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return fromJson(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return fromJson(cs.getString(columnIndex));
    }
}