package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Instantiated connector bound to a saved DB connection.
 */
@Data
@TableName("active_connector")
public class ActiveConnector {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long templateId;
    private Long connectionId;
    private String name;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
