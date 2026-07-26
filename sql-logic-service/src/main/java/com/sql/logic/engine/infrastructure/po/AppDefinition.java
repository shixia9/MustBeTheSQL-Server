package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("agent_app_definition")
public class AppDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    /** team_mode: single_agent | auto_plan | awel_layout */
    private String teamMode;
    /** JSON: agent configs for auto_plan, or flow reference for awel_layout */
    private String teamContext;
    /** JSON array: [{agentName, llmStrategy, resources, promptTemplate}] */
    private String agentDetails;
    /** published: 0=draft, 1=published */
    private Integer published;
    private Long userId;
    private Long workspaceId;
    private Date createTime;
    private Date updateTime;
}
