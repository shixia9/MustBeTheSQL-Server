package com.sql.logic.engine.infrastructure.dialect.model;

import lombok.Data;
import java.util.List;

@Data
public class IndexDTO {
    private String name;
    private String type; // PRIMARY, UNIQUE, NORMAL
    private List<String> columns;
    private String comment;
}
