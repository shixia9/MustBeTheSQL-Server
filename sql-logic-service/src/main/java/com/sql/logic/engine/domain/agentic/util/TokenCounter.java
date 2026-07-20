package com.sql.logic.engine.domain.agentic.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token counting utility for context budget management.
 * <p>
 * Primary strategy: JTokkit.
 * Fallback strategy: character-based estimation (~4 chars/token).
 */
public final class TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(TokenCounter.class);
    private static final double CHARS_PER_TOKEN = 4.0;

    private final TokenizerStrategy strategy;

    public TokenCounter() {
        this.strategy = detectStrategy();
    }

    public TokenCounter(String modelName) {
        this.strategy = detectStrategy(modelName);
    }

    /** Count tokens in the given text. */
    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return strategy.count(text);
    }

    /** Get the model name this counter is configured for. */
    public String modelName() {
        return strategy.modelName();
    }

    // --- Strategy detection ---

    private static TokenizerStrategy detectStrategy() {
        return detectStrategy(null);
    }

    private static TokenizerStrategy detectStrategy(String modelName) {
        try {
            Class.forName("com.knuddels.jtokkit.Encodings");
            try {
                log.info("JTokkit detected — using precise token counting");
                return new JTokkitStrategy(modelName);
            } catch (Exception e) {
                log.info("JTokkit initialization failed ({}), falling back to char estimation", e.getMessage());
            }
        } catch (ClassNotFoundException e) {
            log.info("JTokkit not found — using character-based estimation (~{} chars/token)", CHARS_PER_TOKEN);
        }
        return new CharEstimationStrategy();
    }

    // --- Strategy interface ---

    interface TokenizerStrategy {
        int count(String text);
        String modelName();
    }

    // --- JTokkit-backed strategy ---

    static class JTokkitStrategy implements TokenizerStrategy {
        private final Object encoding; // com.knuddels.jtokkit.api.Encoding
        private final String model;

        JTokkitStrategy(String modelName) {
            this.model = modelName != null ? modelName : "gpt-4";
            this.encoding = resolveEncoding(this.model);
        }

        private static Object resolveEncoding(String model) {
            try {
                // Use reflection to avoid compile-time dependency on JTokkit
                Class<?> registryClass = Class.forName("com.knuddels.jtokkit.Encodings");
                Class<?> encodingTypeClass = Class.forName("com.knuddels.jtokkit.api.EncodingType");
                Object encodingType = resolveEncodingType(encodingTypeClass, model);
                Object registry = registryClass.getMethod("newDefaultEncodingRegistry").invoke(null);
                return registry.getClass().getMethod("getEncoding", encodingTypeClass).invoke(registry, encodingType);
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve JTokkit encoding for model: " + model, e);
            }
        }

        private static Object resolveEncodingType(Class<?> encodingTypeClass, String model) throws Exception {
            // Map common model names to JTokkit encoding types
            String typeName = switch (model.toLowerCase()) {
                case "gpt-4", "gpt-4o", "gpt-4-turbo", "gpt-4o-mini" -> EncodingType.CL100K_BASE.name();
                case "gpt-3.5-turbo", "gpt-3.5-turbo-16k" -> EncodingType.CL100K_BASE.name();
                case "text-davinci-003", "text-davinci-002" -> EncodingType.P50K_BASE.name();
                case "text-embedding-ada-002" -> EncodingType.CL100K_BASE.name();
                default -> EncodingType.CL100K_BASE.name();
            };
            return encodingTypeClass.getField(typeName).get(null);
        }

        @Override
        public int count(String text) {
            try {
                Object encoded = encoding.getClass().getMethod("encode", String.class).invoke(encoding, text);
                return (int) encoded.getClass().getMethod("size").invoke(encoded);
            } catch (Exception e) {
                // Fallback to char estimation on JTokkit failure
                return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
            }
        }

        @Override
        public String modelName() { return model; }
    }

    // --- Character-estimation fallback ---

    static class CharEstimationStrategy implements TokenizerStrategy {
        @Override
        public int count(String text) {
            return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
        }

        @Override
        public String modelName() { return "char-estimate"; }
    }
}
