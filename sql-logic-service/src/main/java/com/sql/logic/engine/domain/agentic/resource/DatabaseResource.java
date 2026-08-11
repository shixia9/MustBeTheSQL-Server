package com.sql.logic.engine.domain.agentic.resource;

import java.util.Map;

/**
 * Database schema resource — provides DDL, column samples, and foreign key
 * information as a prompt fragment for injection into the Agent's system prompt.
 * <p>
 * In Phase 1, this is a simple value holder populated by the caller.
 * In later phases, it will lazily fetch schema from {@code DatabaseMetaDataService}.
 */
public class DatabaseResource implements AgentResource {

    private final String name;
    private final String schemaContext;

    /**
     * @param name          resource identifier (e.g., "mysql-sales-db")
     * @param schemaContext pre-built schema DDL + FK + column sample text
     */
    public DatabaseResource(String name, String schemaContext) {
        this.name = name;
        this.schemaContext = schemaContext != null ? schemaContext : "";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String getPrompt(String observation) {
        if (schemaContext.isEmpty()) {
            return "";
        }
        return """
                ### Database Schema
                %s
                """.formatted(schemaContext);
    }

    /**
     * Build from name and structured schema info.
     */
    public static DatabaseResource of(String name, String ddl, String fkRelations,
                                       Map<String, String> columnSamples) {
        StringBuilder sb = new StringBuilder();
        if (ddl != null && !ddl.isBlank()) {
            sb.append("-- DDL\n").append(ddl).append("\n\n");
        }
        if (fkRelations != null && !fkRelations.isBlank()) {
            sb.append("-- Foreign Key Relations\n").append(fkRelations).append("\n\n");
        }
        if (columnSamples != null && !columnSamples.isEmpty()) {
            sb.append("-- Column Samples\n");
            columnSamples.forEach((col, sample) ->
                    sb.append(col).append(": ").append(sample).append("\n"));
        }
        return new DatabaseResource(name, sb.toString());
    }
}
