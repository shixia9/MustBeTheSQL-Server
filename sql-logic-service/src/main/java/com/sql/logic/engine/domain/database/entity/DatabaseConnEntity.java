package com.sql.logic.engine.domain.database.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DatabaseConnEntity {
    private Long id;
    private Long userId;
    private String dbType;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String dbName;
    private Integer isTest;
}
