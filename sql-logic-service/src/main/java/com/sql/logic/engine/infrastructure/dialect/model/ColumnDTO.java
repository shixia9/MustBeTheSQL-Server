package com.sql.logic.engine.infrastructure.dialect.model;

import lombok.Data;

@Data
public class ColumnDTO {
    private String name;
    private String dataType;
    private String columnType;
    private Boolean nullable;
    private Boolean primaryKey;
    private Boolean autoIncrement;
    private String defaultValue;
    private String comment;
}
