package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Maps to the workflow_definition table (V012 migration).
 * Stores the full JSON DSL {version, name, nodes, edges, variables}.
 */
@Data
@TableName("workflow_definition")
public class WorkflowDefinitionPO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String description;
    private Long userId;
    private Long workspaceId;
    private String version;
    /** Full JSON: {version, name, description, variables, nodes, edges} */
    private String configJson;
    /** DRAFT | ACTIVE | ARCHIVED */
    private String status;
    private Date createTime;
    private Date updateTime;
}
