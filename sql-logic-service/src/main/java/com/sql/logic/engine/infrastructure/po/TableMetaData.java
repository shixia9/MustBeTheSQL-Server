package com.sql.logic.engine.infrastructure.po;

import lombok.Data;
import java.util.List;

@Data
public class TableMetaData {
    private String tableName;
    private String ddl;
    private List<String> columns;
}