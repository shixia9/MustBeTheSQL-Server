package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Phase D2: MCP server connection configuration.
 * <p>
 * Stores connection details for external MCP servers (SSE endpoints or
 * stdio commands). Connected tools are dynamically registered into
 * {@code com.sql.logic.engine.domain.agent.tool.ToolRegistry}.
 */
@Data
@TableName("mcp_server_config")
public class McpServerConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String transportType;   // SSE or STDIO
    private String endpoint;        // URL for SSE, command for STDIO
    private String envVars;         // JSON key-value for environment variables (stdio only)
    private Integer status;         // 0=disabled, 1=active
    private Date createTime;
    private Date updateTime;
}
