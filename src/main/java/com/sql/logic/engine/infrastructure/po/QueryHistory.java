package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("query_history")
public class QueryHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String prompt;
    private Long connectionId;
    private String databaseName;
    private String generatedSql;
    private String modelName;
    private Long executeLatency;
    private Integer tokens;
    private Integer rowCount;
    private BigDecimal cost;
    private Date createTime;
    private Date executeTime;
}
