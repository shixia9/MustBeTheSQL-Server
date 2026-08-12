package com.sql.logic.engine.infrastructure.llm;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.strategy.ThinkingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Service("openAiStrategy")
public class OpenAILLMStrategy implements LLMStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAILLMStrategy.class);

    private final ChatClient chatClient;

    // --- Thinking-mode fields (null when not configured) ---
    private final String thinkingModelName;
    private final RestClient thinkingClient;

    /**
     * Spring auto-configuration constructor — no thinking mode support.
     * The default {@link #chatWithThinking(String)} will fall back to {@link #chat(String)}.
     */
    public OpenAILLMStrategy(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.thinkingModelName = null;
        this.thinkingClient = null;
    }

    /**
     * Factory constructor — supports native thinking mode via direct API calls.
     *
     * @param chatClientBuilder the Spring AI ChatClient builder (for regular calls)
     * @param baseUrl           the LLM API base URL (e.g., https://www.dmxapi.cn)
     * @param apiKey            the API key for authentication
     * @param modelName         the model name (e.g., doubao-seed-2-1-pro-260628)
     */
    public OpenAILLMStrategy(ChatClient.Builder chatClientBuilder,
                             String baseUrl, String apiKey, String modelName) {
        this.chatClient = chatClientBuilder.build();
        this.thinkingModelName = modelName;
        if (baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank() && modelName != null) {
            // Normalise: strip trailing slashes and /v1 so we always append /v1/chat/completions
            String normalized = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
            this.thinkingClient = RestClient.builder()
                    .baseUrl(normalized)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .defaultHeader("Content-Type", "application/json")
                    .build();
        } else {
            this.thinkingClient = null;
        }
    }

    @Override
    public Flux<String> generateSqlStream(String promptStr, BiConsumer<Integer, String> tokenAndSqlCallback) {
        Prompt prompt = new Prompt(promptStr);

        return Flux.defer(() -> {
            JsonStreamParser parser = new JsonStreamParser();
            AtomicInteger maxTokens = new AtomicInteger(0);

            Flux<String> streamContent = chatClient.prompt(prompt)
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                            Integer totalTokens = response.getMetadata().getUsage().getTotalTokens();
                            if (totalTokens != null) {
                                maxTokens.set(Math.max(maxTokens.get(), totalTokens.intValue()));
                            }
                        }
                    })
                    .flatMapIterable(response -> {
                        String chunk = response.getResult() != null && response.getResult().getOutput() != null
                                ? response.getResult().getOutput().getText() : "";
                        return parser.processChunk(chunk);
                    });

            // processComplete() runs first, then callback fires in doFinally()
            // This ensures the SQL is fully extracted before the callback reads it
            Flux<String> completeContent = Flux.fromIterable(parser.processComplete());

            return Flux.concat(streamContent, completeContent)
                    .doFinally(signalType -> {
                        // Callback fires AFTER processComplete() has run, ensuring extractedSql is complete
                        if (tokenAndSqlCallback != null) {
                            tokenAndSqlCallback.accept(maxTokens.get(), parser.getExtractedSql());
                        }
                    });
        });
    }

    @Override
    public String generateSql(String promptStr, BiConsumer<Integer, String> tokenAndSqlCallback) {
        Prompt prompt = new Prompt(promptStr);
        var response = chatClient.prompt(prompt).call().chatResponse();

        String generatedContent = response != null && response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText() : "";

        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Integer totalTokens = response.getMetadata().getUsage().getTotalTokens();
            if (totalTokens != null && totalTokens > 0 && tokenAndSqlCallback != null) {
                tokenAndSqlCallback.accept(totalTokens.intValue(), generatedContent);
            }
        }

        return generatedContent;
    }

    /**
     * {@inheritDoc}
     *
     * <p>When {@code thinkingClient} is configured, makes a direct HTTP POST to
     * the OpenAI-compatible {@code /v1/chat/completions} endpoint with
     * {@code thinking: {type: "enabled"}} in the request body. The API returns
     * {@code reasoning_content} alongside {@code content} in the response —
     * both are extracted and returned in a {@link ThinkingResult}.
     *
     * <p>When {@code thinkingClient} is null (Spring auto-configured bean) or
     * the API call fails, falls back to {@link #chat(String)} with empty reasoning.
     */
    @Override
    @SuppressWarnings("unchecked")
    public ThinkingResult chatWithThinking(String prompt) {
        if (thinkingClient == null || thinkingModelName == null) {
            return new ThinkingResult(chat(prompt));
        }
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", thinkingModelName);
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            requestBody.put("thinking", Map.of("type", "enabled"));
            requestBody.put("temperature", 0.2);

            Map<String, Object> response = thinkingClient.post()
                    .uri("/v1/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return new ThinkingResult(chat(prompt));
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return new ThinkingResult(chat(prompt));
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                return new ThinkingResult(chat(prompt));
            }

            String content = (String) message.getOrDefault("content", "");
            String reasoning = (String) message.getOrDefault("reasoning_content", "");

            return new ThinkingResult(
                    content != null ? content : "",
                    reasoning != null ? reasoning : ""
            );
        } catch (Exception e) {
            log.warn("chatWithThinking failed, falling back to regular chat: {}", e.getMessage());
            return new ThinkingResult(chat(prompt));
        }
    }
}