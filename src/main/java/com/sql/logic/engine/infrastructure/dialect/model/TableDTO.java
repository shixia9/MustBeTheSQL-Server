package com.sql.logic.engine.infrastructure.dialect.model;

import lombok.Data;

@Data
public class TableDTO {
    private String name;
    private String type; // TABLE or VIEW
    private String comment;
}
