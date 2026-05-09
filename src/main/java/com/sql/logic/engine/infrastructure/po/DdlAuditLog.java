package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ddl_audit_log")
public class DdlAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long connectionId;
    private String clientIp;
    private String sqlScript;
    private Long executeLatency;
    private String status; // SUCCESS, FAILED, TIMEOUT
    private String errorMessage;
    private Date createTime;
}