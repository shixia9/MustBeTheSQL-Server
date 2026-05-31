package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sql.logic.engine.infrastructure.dao.AesEncryptTypeHandler;
import lombok.Data;

@Data
@TableName(value = "db_connection_conf", autoResultMap = true)
public class DbConnectionConf {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String dbType;
    private String host;
    private Integer port;
    private String username;

    @TableField(typeHandler = AesEncryptTypeHandler.class)
    private String password;

    private String dbName;
    private Integer isTest;
}