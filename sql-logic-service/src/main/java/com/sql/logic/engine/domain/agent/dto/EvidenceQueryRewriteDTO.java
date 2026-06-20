package com.sql.logic.engine.domain.agent.dto;

/**
 * DTO for the evidence query rewrite node's structured output.
 * <p>
 * Used with BeanOutputConverter to enforce JSON output from the LLM,
 * replacing the fragile manual string parsing of {"standalone_query": "..."}.
 */
public record EvidenceQueryRewriteDTO(
    String standalone_query
) {}