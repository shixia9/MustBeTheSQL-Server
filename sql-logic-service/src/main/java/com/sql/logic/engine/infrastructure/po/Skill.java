package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sql.logic.engine.infrastructure.dao.JsonStringListTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight Skill entity.
 * <p>
 * A Skill is a packaged prompt template optionally bound to a set of tool
 * names ({@code bindTools}), invocable via the "/" command palette. Mirrors the
 * {@code skill} table ({@code V015__skill_table.sql}). {@code bindTools} is
 * stored as a JSON array of tool-name strings via {@link JsonStringListTypeHandler},
 * the same pattern used by {@code MemoryItem.tags}.
 */
@Data
@TableName(value = "skill", autoResultMap = true)
public class Skill {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String promptTemplate;
    @TableField(typeHandler = JsonStringListTypeHandler.class)
    private List<String> bindTools;
    private String visibility;   // "public" | "private"
    private Integer status;      // 1=active, 0=archived
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
