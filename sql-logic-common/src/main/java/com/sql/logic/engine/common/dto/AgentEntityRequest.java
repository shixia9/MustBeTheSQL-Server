package com.sql.logic.engine.common.dto;

import lombok.Data;

import java.util.List;

/**
 * Phase B (B4) Agent Studio create/update request.
 * <p>
 * On create, {@code id} is null. On update, {@code id} is required and must belong to
 * the authenticated user. {@code toolsConfig}/{@code ragConfig} are accepted as either
 * structured fields or a raw JSON string — the structured form wins when present.
 */
@Data
public class AgentEntityRequest {
    private Long id;
    private String name;
    private String description;
    private String avatar;
    private String systemPrompt;
    private String welcomeMessage;
    /** Structured tool toggles, e.g. ["sql","schema","python"]. */
    private List<String> enabledTools;
    /** RAG parameters (structured). */
    private Integer topK;
    private Double scoreThreshold;
    private Boolean ragEnabled;
    private Boolean memoryEnabled;
    private Boolean isDefault;
}
