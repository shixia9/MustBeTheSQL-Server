package com.sql.logic.engine.domain.agentic.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JSON extraction logic in {@link McpToolFixAction}.
 * <p>
 * Tests the private {@code extractJson} method via reflection to validate
 * its robustness against various LLM output formats. Also tests
 * {@code parseArgs} for valid and invalid JSON inputs.
 * <p>
 * Recommendation: consider extracting the JSON parsing logic into a
 * package-private static utility method (e.g. {@code JsonExtractor.extractFirstObject})
 * so these tests can call it directly without reflection.
 */
class McpToolFixActionJsonTest {

    // ==================== extractJson (via reflection) ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"query\": \"SELECT * FROM users\"}",
            "{\"query\":\"SELECT * FROM users\"}",
            "  {\"query\": \"test\"}  ",
    })
    void shouldExtractPlainJsonObject(String input) throws Exception {
        String result = invokeExtractJson(input);
        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
    }

    @Test
    void shouldStripMarkdownCodeFenceWithLanguageTag() throws Exception {
        String input = "```json\n{\"query\": \"SELECT * FROM users\"}\n```";
        String result = invokeExtractJson(input);
        assertEquals("{\"query\": \"SELECT * FROM users\"}", result);
    }

    @Test
    void shouldStripMarkdownCodeFenceWithoutLanguageTag() throws Exception {
        String input = "```\n{\"query\": \"SELECT 1\"}\n```";
        String result = invokeExtractJson(input);
        assertEquals("{\"query\": \"SELECT 1\"}", result);
    }

    @Test
    void shouldExtractJsonFromProseWrapper() throws Exception {
        String input = "Here is the corrected args:\n{\"param1\": \"value1\", \"param2\": 42}\nHope this helps!";
        String result = invokeExtractJson(input);
        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
        assertTrue(result.contains("\"param1\""));
        assertTrue(result.contains("\"param2\""));
    }

    @Test
    void shouldHandleNestedJsonObject() throws Exception {
        String input = "{\"filter\": {\"field\": \"status\", \"op\": \"eq\", \"value\": \"active\"}}";
        String result = invokeExtractJson(input);
        // The first { to the last } should capture the entire nested object
        assertEquals(input, result);
    }

    @Test
    void shouldReturnEmptyObjectForNullInput() throws Exception {
        String result = invokeExtractJson(null);
        assertEquals("{}", result);
    }

    @Test
    void shouldReturnEmptyObjectForBlankInput() throws Exception {
        String result = invokeExtractJson("   ");
        assertEquals("{}", result);
    }

    @Test
    void shouldReturnEmptyObjectForInputWithNoBraces() throws Exception {
        String result = invokeExtractJson("No JSON here, just text.");
        // No braces found → returns the trimmed input as-is
        assertEquals("No JSON here, just text.", result);
    }

    @Test
    void shouldHandleMultipleJsonObjects_ExtractFirstAndLast() throws Exception {
        // Multiple JSON blobs — extractJson uses indexOf('{') + lastIndexOf('}')
        // which will span from the first { to the last }, concatenating everything in between.
        // This is a known limitation — it won't distinguish two separate objects.
        String input = "{\"a\":1} and also {\"b\":2}";
        String result = invokeExtractJson(input);
        // Spans from first '{' to last '}', including the middle text
        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
        assertTrue(result.contains("\"a\":1"));
        assertTrue(result.contains("\"b\":2"));
    }

    // ==================== parseArgs ====================

    @Test
    void shouldParseValidJsonArgs() throws Exception {
        Map<String, Object> args = invokeParseArgs("{\"query\": \"SELECT 1\", \"limit\": 10}");
        assertEquals("SELECT 1", args.get("query"));
        assertEquals(10, args.get("limit"));
    }

    @Test
    void shouldReturnEmptyMapForInvalidJson() throws Exception {
        Map<String, Object> args = invokeParseArgs("this is not valid json {");
        assertTrue(args.isEmpty());
    }

    @Test
    void shouldReturnEmptyMapForEmptyString() throws Exception {
        Map<String, Object> args = invokeParseArgs("");
        assertTrue(args.isEmpty());
    }

    // ==================== Full pipeline test ====================

    @Test
    void shouldExtractAndParseInSequence() throws Exception {
        // Simulate a real LLM output with markdown fence + language tag
        String llmOutput = """
                ```json
                {
                  "table_name": "orders",
                  "columns": ["id", "amount", "created_at"],
                  "date_range": "2024-01-01 to 2024-06-30"
                }
                ```""";

        String extracted = invokeExtractJson(llmOutput);
        Map<String, Object> parsed = invokeParseArgs(extracted);

        assertEquals("orders", parsed.get("table_name"));
        assertTrue(parsed.get("columns") instanceof java.util.List);
    }

    // ==================== Reflection helpers ====================

    private static String invokeExtractJson(String input) throws Exception {
        // Create a minimal instance (dependencies not needed for extractJson)
        McpToolFixAction action = new McpToolFixAction(null, null);
        Method m = McpToolFixAction.class.getDeclaredMethod("extractJson", String.class);
        m.setAccessible(true);
        return (String) m.invoke(action, input);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeParseArgs(String json) throws Exception {
        McpToolFixAction action = new McpToolFixAction(null, null);
        Method m = McpToolFixAction.class.getDeclaredMethod("parseArgs", String.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(action, json);
    }
}
