package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Reusable connector definition (REST/JDBC/FILE/KAFKA etc.) owned by a user.
 */
@Data
@TableName("connector_template")
public class ConnectorTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String connectorType;
    private String config;
    private String description;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
