package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class LlmConfigCreateRequest {
    private String configName;     // Required, display label
    private String providerType;   // Required: "OPENAI_COMPATIBLE" or "ANTHROPIC"
    private String baseUrl;        // Optional, null = provider default
    private String apiKey;         // Required
    private String modelName;     // Optional, e.g. "gpt-4o"
    private Boolean isDefault;     // Optional, whether to set as user's default
}