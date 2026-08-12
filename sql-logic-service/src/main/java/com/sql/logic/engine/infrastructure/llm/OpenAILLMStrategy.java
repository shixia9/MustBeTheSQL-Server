package com.sql.logic.engine.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.strategy.ThinkingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Service("openAiStrategy")
public class OpenAILLMStrategy implements LLMStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAILLMStrategy.class);
    private static final ObjectMapper SSE_MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    // --- Thinking-mode fields (null when not configured) ---
    private final String thinkingBaseUrl;
    private final String thinkingApiKey;
    private final String thinkingModelName;
    private final RestClient thinkingClient;
    private final HttpClient streamingHttpClient;

    /**
     * Spring auto-configuration constructor — no thinking mode support.
     * The default {@link #chatWithThinking(String)} will fall back to {@link #chat(String)}.
     */
    public OpenAILLMStrategy(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.thinkingBaseUrl = null;
        this.thinkingApiKey = null;
        this.thinkingModelName = null;
        this.thinkingClient = null;
        this.streamingHttpClient = null;
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
        if (baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank() && modelName != null) {
            // Normalise: strip trailing slashes and /v1 so we always append /v1/chat/completions
            String normalized = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
            this.thinkingBaseUrl = normalized;
            this.thinkingApiKey = apiKey;
            this.thinkingModelName = modelName;
            this.thinkingClient = RestClient.builder()
                    .baseUrl(normalized)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .defaultHeader("Content-Type", "application/json")
                    .build();
            this.streamingHttpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        } else {
            this.thinkingBaseUrl = null;
            this.thinkingApiKey = null;
            this.thinkingModelName = null;
            this.thinkingClient = null;
            this.streamingHttpClient = null;
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

    // ======================== Native Thinking Mode (non-streaming fallback) ========================

    /**
     * {@inheritDoc}
     *
     * <p>Non-streaming fallback. When possible, prefer {@link #chatWithThinkingStream}
     * which provides real-time reasoning chunks via SSE streaming.
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

    // ======================== Native Thinking Mode (streaming) ========================

    /**
     * {@inheritDoc}
     *
     * <p>When {@code streamingHttpClient} is configured, makes a streaming HTTP
     * POST to the OpenAI-compatible {@code /v1/chat/completions} endpoint with
     * {@code stream: true} and {@code thinking: {type: "enabled"}} in the request
     * body. The SSE response is parsed line by line:
     * <ul>
     *   <li>{@code delta.reasoning_content} chunks → {@code onReasoningChunk} callback
     *       (emitted to frontend immediately for typewriter effect)</li>
     *   <li>{@code delta.content} chunks → accumulated for the final output</li>
     * </ul>
     *
     * <p>When streaming is not configured or fails, falls back to
     * {@link #chatWithThinking(String)} which emits the full reasoning as a
     * single chunk.
     */
    @Override
    @SuppressWarnings("unchecked")
    public ThinkingResult chatWithThinkingStream(String prompt, Consumer<String> onReasoningChunk) {
        if (streamingHttpClient == null || thinkingBaseUrl == null || thinkingModelName == null) {
            return LLMStrategy.super.chatWithThinkingStream(prompt, onReasoningChunk);
        }
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", thinkingModelName);
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            requestBody.put("thinking", Map.of("type", "enabled"));
            requestBody.put("stream", true);
            requestBody.put("temperature", 0.2);

            String jsonBody = SSE_MAPPER.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(thinkingBaseUrl + "/v1/chat/completions"))
                    .header("Authorization", "Bearer " + thinkingApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<InputStream> response = streamingHttpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("chatWithThinkingStream HTTP {}: {}", response.statusCode(),
                        errBody.length() > 500 ? errBody.substring(0, 500) + "..." : errBody);
                return LLMStrategy.super.chatWithThinkingStream(prompt, onReasoningChunk);
            }

            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) break;
                    if (data.isEmpty()) continue;

                    Map<String, Object> chunk = SSE_MAPPER.readValue(data, Map.class);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                    if (delta == null) continue;

                    String reasoningDelta = (String) delta.get("reasoning_content");
                    String contentDelta = (String) delta.get("content");

                    if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                        reasoning.append(reasoningDelta);
                        onReasoningChunk.accept(reasoningDelta);
                    }
                    if (contentDelta != null && !contentDelta.isEmpty()) {
                        content.append(contentDelta);
                    }
                }
            }

            return new ThinkingResult(content.toString(), reasoning.toString());
        } catch (Exception e) {
            log.warn("chatWithThinkingStream failed, falling back to non-streaming: {}", e.getMessage());
            return LLMStrategy.super.chatWithThinkingStream(prompt, onReasoningChunk);
        }
    }
}
