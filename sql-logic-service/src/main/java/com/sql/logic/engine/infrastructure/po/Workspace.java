package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("workspace")
public class Workspace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private Integer status;  // 1=active, 0=inactive
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
