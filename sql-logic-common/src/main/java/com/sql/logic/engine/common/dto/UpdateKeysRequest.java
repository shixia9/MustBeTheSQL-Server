package com.sql.logic.engine.common.dto;

import lombok.Data;

/**
 * Request DTO for updating LLM API keys.
 * Secrets (apiKey, baseUrl) are passed in the request body instead of URL params
 * to prevent credential leakage in server logs and browser history.
 */
@Data
public class UpdateKeysRequest {
    private String apiKey;
    private String baseUrl;
}