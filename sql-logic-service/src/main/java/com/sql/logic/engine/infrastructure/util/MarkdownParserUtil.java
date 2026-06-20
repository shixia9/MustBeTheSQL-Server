package com.sql.logic.engine.infrastructure.util;

import java.util.regex.Pattern;

/**
 * Utility for stripping markdown code fences from LLM output.
 * LLMs often wrap SQL or code in ```sql ... ``` blocks;
 * this utility extracts the raw content.
 */
public final class MarkdownParserUtil {

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "(?s)```(?:\\w+)?\\s*\\n?(.*?)\\n?```"
    );

    private MarkdownParserUtil() {}

    /**
     * Strip markdown code fences from LLM output.
     * Handles patterns like:
     * <pre>
     * ```sql
     * SELECT * FROM users
     * ```
     * </pre>
     * Returns the inner content without the fences.
     * If no fences are found, returns the original text trimmed.
     */
    public static String extractRawText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // Try to match code fence pattern
        var matcher = CODE_FENCE_PATTERN.matcher(text.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }
}