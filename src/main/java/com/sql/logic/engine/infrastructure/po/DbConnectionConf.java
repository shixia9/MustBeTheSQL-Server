package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("db_connection_conf")
public class DbConnectionConf {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String dbType;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String dbName;
    private Integer isTest;
}
