package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sql.logic.engine.infrastructure.dao.JsonStringListTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase B — Agent memory item.
 * Mirrors AgentX {@code memory_items} structure with normalized Long primary keys.
 * Tags stored as JSON via {@link JsonStringListTypeHandler} for type-safe access.
 */
@Data
@TableName(value = "memory_item", autoResultMap = true)
public class MemoryItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long workspaceId;
    private String type;       // PROFILE | TASK | FACT | EPISODIC
    private String content;
    private BigDecimal importance;
    @TableField(typeHandler = JsonStringListTypeHandler.class)
    private List<String> tags;
    private String sourceSessionId;
    private String dedupeHash;
    private Integer status;   // 1=active, 0=archived
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}