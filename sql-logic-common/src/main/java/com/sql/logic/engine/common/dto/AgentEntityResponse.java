package com.sql.logic.engine.common.dto;

import lombok.Data;

import java.util.List;

/**
 * Phase B (B4) Agent Studio response DTO. Mirrors {@code agent_entity} plus a parsed
 * view of the JSON config columns for the frontend.
 */
@Data
public class AgentEntityResponse {
    private Long id;
    private Long userId;
    private Long workspaceId;
    private String name;
    private String description;
    private String avatar;
    private String systemPrompt;
    private String welcomeMessage;
    private String toolsConfig;     // raw JSON string
    private String ragConfig;       // raw JSON string
    private Boolean memoryEnabled;
    private Boolean isDefault;
    private Integer status;
    private String createTime;
    private String updateTime;
    /** Parsed tool toggles (convenience for the frontend). */
    private List<String> enabledTools;
}
