package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.util.Date;

@Data
@TableName("user_info")
public class UserInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String email;
    private String avatar;
    private Integer status; // 0: Banned, 1: Active, 2: Frozen, -1: Cancelled
    private Integer tokenQuota;
    private Date createTime;
    private Date updateTime;
    
    @TableField(exist = false)
    private String apiKey;
    
    @TableField(exist = false)
    private String baseUrl;
}
