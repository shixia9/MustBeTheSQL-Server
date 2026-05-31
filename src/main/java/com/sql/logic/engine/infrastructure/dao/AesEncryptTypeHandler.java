package com.sql.logic.engine.infrastructure.dao;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sql.logic.engine.infrastructure.util.AesUtil;

/**
 * AES encryption TypeHandler for MyBatis-Plus.
 * Encrypts values on write, decrypts on read.
 * Uses AesUtil static methods for compatibility with MyBatis type handler instantiation.
 *
 * Migration-safe: If a value cannot be decrypted (because it was stored as plaintext
 * before encryption was enabled), the original value is returned as-is. This allows
 * seamless migration from plaintext to encrypted storage without a data migration step.
 */
@MappedTypes(String.class)
public class AesEncryptTypeHandler extends BaseTypeHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(AesEncryptTypeHandler.class);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, AesUtil.encryptStatic(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String raw = rs.getString(columnName);
        return decryptSafe(raw, columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String raw = rs.getString(columnIndex);
        return decryptSafe(raw, "column_" + columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String raw = cs.getString(columnIndex);
        return decryptSafe(raw, "parameter_" + columnIndex);
    }

    /**
     * Attempt to decrypt the value. If decryption fails (because the value was stored
     * as plaintext before encryption was enabled), return the original value as-is.
     * This provides seamless migration from plaintext to encrypted storage.
     */
    private String decryptSafe(String value, String fieldDesc) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            return AesUtil.decryptStatic(value);
        } catch (Exception e) {
            // Value was stored as plaintext (pre-migration) — return as-is
            log.debug("Field {} contains a non-encrypted value, returning as plaintext", fieldDesc);
            return value;
        }
    }
}