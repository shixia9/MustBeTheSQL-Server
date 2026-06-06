package com.sql.logic.engine.infrastructure.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * AES encryption utility.
 * Key is externalized to application.yml / environment variables for security.
 * Used as a Spring bean to support dependency injection.
 * Also provides static methods for MyBatis TypeHandler compatibility.
 */
@Component
public class AesUtil {

    private static final String AES_KEY_ENV = "AES_KEY";

    @Value("${engine.crypto.aes-key}")
    private String configuredKey;

    private AES aes;

    /** Singleton instance set after Spring initialization. */
    private static AesUtil INSTANCE;

    @PostConstruct
    public void init() {
        byte[] keyBytes = Base64.getDecoder().decode(configuredKey);
        this.aes = SecureUtil.aes(keyBytes);
        INSTANCE = this;
    }

    /**
     * Encrypt content using AES.
     *
     * @param content the plaintext content to encrypt
     * @return hex-encoded encrypted string, or null/blank if input is blank
     */
    public String encrypt(String content) {
        if (StringUtils.isBlank(content)) {
            return content;
        }
        return aes.encryptHex(content);
    }

    /**
     * Decrypt content using AES.
     *
     * @param content hex-encoded encrypted string
     * @return decrypted plaintext string, or null/blank if input is blank
     */
    public String decrypt(String content) {
        if (StringUtils.isBlank(content)) {
            return content;
        }
        return aes.decryptStr(content);
    }

    /**
     * Encrypt using the singleton instance.
     * Prefer injecting AesUtil where possible.
     */
    public static String encryptStatic(String content) {
        if (INSTANCE == null) {
            // Fallback: read key from environment variable when Spring context is not yet ready
            String envKey = System.getenv(AES_KEY_ENV);
            if (envKey == null || envKey.isEmpty()) {
                throw new IllegalStateException(
                    "AES_KEY environment variable is not set. "
                    + "Cannot encrypt before Spring context initialization.");
            }
            AesUtil temp = new AesUtil();
            temp.configuredKey = envKey;
            temp.init();
            return temp.encrypt(content);
        }
        return INSTANCE.encrypt(content);
    }

    /**
     * Decrypt using the singleton instance.
     * Prefer injecting AesUtil where possible.
     */
    public static String decryptStatic(String content) {
        if (INSTANCE == null) {
            // Fallback: read key from environment variable when Spring context is not yet ready
            String envKey = System.getenv(AES_KEY_ENV);
            if (envKey == null || envKey.isEmpty()) {
                throw new IllegalStateException(
                    "AES_KEY environment variable is not set. "
                    + "Cannot decrypt before Spring context initialization.");
            }
            AesUtil temp = new AesUtil();
            temp.configuredKey = envKey;
            temp.init();
            return temp.decrypt(content);
        }
        return INSTANCE.decrypt(content);
    }
}