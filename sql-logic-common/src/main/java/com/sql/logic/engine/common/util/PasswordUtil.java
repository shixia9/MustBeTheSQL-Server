package com.sql.logic.engine.common.util;

import cn.hutool.crypto.digest.BCrypt;

/**
 * Password utility for secure hashing and verification.
 * Uses BCrypt for password hashing to prevent rainbow table attacks.
 * All new passwords are hashed with BCrypt.
 * Supports transparent migration from plaintext passwords.
 */
public class PasswordUtil {

    private static final String BCrypt_PREFIX = "$2";
    private static final int BCRYPT_LOG_ROUNDS = 10;

    /**
     * Hash a plaintext password using BCrypt.
     *
     * @param plaintextPassword the plaintext password to hash
     * @return the BCrypt hash
     */
    public static String hash(String plaintextPassword) {
        return BCrypt.hashpw(plaintextPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
    }

    /**
     * Verify a plaintext password against a stored hash.
     * Supports both BCrypt hashes and legacy plaintext passwords for migration.
     *
     * @param plaintextPassword the plaintext password to verify
     * @param storedPassword    the stored password (BCrypt hash or legacy plaintext)
     * @return true if the password matches
     */
    public static boolean verify(String plaintextPassword, String storedPassword) {
        if (storedPassword == null || plaintextPassword == null) {
            return false;
        }
        // BCrypt hashes start with "$2a$", "$2b$", or "$2y$"
        if (storedPassword.startsWith(BCrypt_PREFIX)) {
            return BCrypt.checkpw(plaintextPassword, storedPassword);
        }
        // Legacy plaintext comparison for migration period
        return plaintextPassword.equals(storedPassword);
    }

    /**
     * Check if a stored password is a BCrypt hash (already migrated).
     *
     * @param storedPassword the stored password to check
     * @return true if it's a BCrypt hash
     */
    public static boolean isBcryptHash(String storedPassword) {
        return storedPassword != null && storedPassword.startsWith(BCrypt_PREFIX);
    }
}