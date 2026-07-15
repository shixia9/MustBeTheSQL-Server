package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("workspace_member")
public class WorkspaceMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workspaceId;
    private Long userId;
    private String role;  // OWNER, ADMIN, MEMBER, VIEWER
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
