package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sql.logic.engine.infrastructure.dao.AesEncryptTypeHandler;

import lombok.Data;

import java.util.Date;

@Data
@TableName(value = "user_llm_api_key", autoResultMap = true)
public class UserLlmApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String strategyName;
    private String baseUrl;
    @TableField(typeHandler = AesEncryptTypeHandler.class)
    private String apiKey;
    private Integer status; // 0: Inactive, 1: Active
    private Date createTime;
    private Date updateTime;
}