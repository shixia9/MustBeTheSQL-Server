package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Phase D3: Immutable snapshot of an Agent's full configuration at publish time.
 */
@Data
@TableName("agent_version")
public class AgentVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Integer versionNumber;
    private String snapshotJson;
    private Long publishedBy;
    private Date publishTime;
}
