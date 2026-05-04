package com.sql.logic.engine.infrastructure.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

import java.util.Base64;

import org.apache.commons.lang3.StringUtils;

/**
 * AES加解密
 */
public class AesUtil {

    private static String key = "uTfe6WtWICU/6rk0Gr7qKrAvHaRvQj+HRaHKvSe9UJI=";
    private static AES aes = SecureUtil.aes(Base64.getDecoder().decode(key));

    public static String encrypt(String content) {
        //判空修改
        if (StringUtils.isBlank(content)) {
            return content;
        }
        return aes.encryptHex(content);
    }

    public static String decrypt(String content) {
        //判空修改
        if (StringUtils.isBlank(content)) {
            return content;
        }
        return aes.decryptStr(content);
    }
}
