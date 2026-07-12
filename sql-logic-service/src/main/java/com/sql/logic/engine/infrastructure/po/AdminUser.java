package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("admin_user")
public class AdminUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String role;
    private Integer status;
    private Long createdBy;
    private Date createTime;

    public boolean isActive() { return status != null && status == 1; }
    public boolean isSuperAdmin() { return "SUPER_ADMIN".equals(role); }
}
