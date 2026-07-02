package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("workspace_invitation")
public class WorkspaceInvitation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workspaceId;
    private Long creatorId;
    private String token;
    private String role;
    private LocalDateTime expiresAt;
    private Integer maxUses;
    private Integer useCount;
    private Integer isActive;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
