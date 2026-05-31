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
 * Key is externalized to application.yml for security.
 * Used as a Spring bean to support dependency injection.
 * Also provides static methods for MyBatis TypeHandler compatibility.
 */
@Component
public class AesUtil {

    /** Default key used as fallback when Spring context is not yet initialized. */
    private static final String DEFAULT_KEY = "uTfe6WtWICU/6rk0Gr7qKrAvHaRvQj+HRaHKvSe9UJI=";

    @Value("${engine.crypto.aes-key:uTfe6WtWICU/6rk0Gr7qKrAvHaRvQj+HRaHKvSe9UJI=}")
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
            // Fallback for early initialization before Spring context is ready
            AesUtil temp = new AesUtil();
            temp.configuredKey = DEFAULT_KEY;
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
            // Fallback for early initialization before Spring context is ready
            AesUtil temp = new AesUtil();
            temp.configuredKey = DEFAULT_KEY;
            temp.init();
            return temp.decrypt(content);
        }
        return INSTANCE.decrypt(content);
    }
}