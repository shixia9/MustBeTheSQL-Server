package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent configuration entity (Phase B B4 — Studio).
 * <p>
 * One row = one user-managed Agent: name/avatar, system prompt, welcome message,
 * tool toggles, RAG params, and a memory-injection switch. A run with no agent config
 * falls back to the built-in defaults, so every column except the identity/ownership
 * ones is nullable.
 */
@Data
@TableName(value = "agent_entity", autoResultMap = true)
public class AgentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long workspaceId;
    private String name;
    private String description;
    private String avatar;
    private String systemPrompt;
    private String welcomeMessage;
    /** JSON string: {"sql":true,"schema":true,"python":true,"sample":true}. */
    private String toolsConfig;
    /** JSON string: {"topK":5,"scoreThreshold":0.6,"enabled":true}. */
    private String ragConfig;
    private Integer memoryEnabled;
    private Integer isDefault;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
