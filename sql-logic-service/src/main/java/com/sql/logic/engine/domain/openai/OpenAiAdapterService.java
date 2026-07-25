package com.sql.logic.engine.domain.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.AgenticRunner;
import com.sql.logic.engine.trigger.http.dto.ChatCompletionRequest;
import com.sql.logic.engine.trigger.http.dto.ChatCompletionResponse;
import com.sql.logic.engine.trigger.http.dto.ChatCompletionStreamResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Adapter that translates OpenAI-compatible ChatCompletion requests
 * into internal AgenticRunner calls and maps responses back to OpenAI format.
 */
@Service
public class OpenAiAdapterService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAdapterService.class);

    private final AgenticRunner agenticRunner;

    public OpenAiAdapterService(AgenticRunner agenticRunner) {
        this.agenticRunner = agenticRunner;
    }

    /**
     * Extract the last user message content from the messages array.
     */
    public String extractUserQuestion(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = request.getMessages().get(i);
            if ("user".equals(msg.getRole())) {
                Object content = msg.getContent();
                if (content instanceof String s) return s;
                if (content instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof java.util.Map<?, ?> map) {
                        Object text = map.get("text");
                        return text != null ? text.toString() : "";
                    }
                    return first.toString();
                }
                return content != null ? content.toString() : "";
            }
        }
        return "";
    }

    /**
     * Non-streaming completion.
     */
    public ChatCompletionResponse complete(ChatCompletionRequest request,
                                            Long userId, Long connectionId,
                                            Long llmConfigId, Long workspaceId) {
        String question = extractUserQuestion(request);
        if (question.isBlank()) {
            throw new IllegalArgumentException("No user message found in request");
        }

        String requestId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
        long created = System.currentTimeMillis() / 1000;

        try {
            AgenticRunner.AgentRunHandle handle = agenticRunner.execute(
                    connectionId, question, userId, llmConfigId, workspaceId,
                    null, "", false);

            // Collect the full response from SSE stream (blocking for non-streaming)
            String content = handle.getUnifiedSseFlux()
                    .take(Duration.ofSeconds(120))
                    .collectList()
                    .map(events -> extractTextFromSseEvents(events))
                    .block(Duration.ofSeconds(130));

            String responseContent = content != null ? content : "No response generated";

            ChatCompletionResponse response = new ChatCompletionResponse();
            response.setId(requestId);
            response.setCreated(created);
            response.setModel(request.getModel());

            ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
            choice.setIndex(0);
            ChatCompletionResponse.Message msg = new ChatCompletionResponse.Message();
            msg.setRole("assistant");
            msg.setContent(responseContent);
            choice.setMessage(msg);
            choice.setFinishReason("stop");
            response.setChoices(List.of(choice));

            ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage();
            usage.setPromptTokens(0);
            usage.setCompletionTokens(0);
            usage.setTotalTokens(0);
            response.setUsage(usage);

            return response;

        } catch (Exception e) {
            log.error("OpenAI completion failed", e);
            throw new RuntimeException("Completion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Streaming completion returning SSE Flux.
     */
    public Flux<String> streamComplete(ChatCompletionRequest request,
                                        Long userId, Long connectionId,
                                        Long llmConfigId, Long workspaceId) {
        String question = extractUserQuestion(request);
        if (question.isBlank()) {
            return Flux.error(new IllegalArgumentException("No user message found in request"));
        }

        String requestId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
        long created = System.currentTimeMillis() / 1000;

        try {
            AgenticRunner.AgentRunHandle handle = agenticRunner.execute(
                    connectionId, question, userId, llmConfigId, workspaceId,
                    null, "", false);

            return handle.getUnifiedSseFlux()
                    .take(Duration.ofSeconds(300))
                    .filter(s -> s != null && !s.isBlank() && s.startsWith("{"))
                    .map(sseEvent -> {
                        ChatCompletionStreamResponse chunk = new ChatCompletionStreamResponse();
                        chunk.setId(requestId);
                        chunk.setCreated(created);
                        chunk.setModel(request.getModel());

                        ChatCompletionStreamResponse.Choice choice = new ChatCompletionStreamResponse.Choice();
                        choice.setIndex(0);
                        ChatCompletionStreamResponse.Delta delta = new ChatCompletionStreamResponse.Delta();
                        // Extract text content from SSE event
                        String text = extractTextFromSseEvent(sseEvent);
                        delta.setContent(text);
                        choice.setDelta(delta);
                        choice.setFinishReason(null);
                        chunk.setChoices(List.of(choice));
                        return "data: " + toJson(chunk) + "\n\n";
                    })
                    .concatWith(Mono.just("data: [DONE]\n\n"))
                    .onErrorResume(e -> {
                        log.error("Stream error", e);
                        return Mono.just("data: {\"error\":{\"message\":\"" + e.getMessage() + "\"}}\n\n");
                    });

        } catch (Exception e) {
            log.error("OpenAI stream failed", e);
            return Flux.error(e);
        }
    }

    private String extractTextFromSseEvents(List<String> events) {
        StringBuilder sb = new StringBuilder();
        for (String event : events) {
            String text = extractTextFromSseEvent(event);
            if (text != null && !text.isBlank()) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private String extractTextFromSseEvent(String sseEvent) {
        try {
            ObjectMapper om = new ObjectMapper();
            var node = om.readTree(sseEvent);
            // Try to extract content from agent response events
            if (node.has("content")) return node.get("content").asText();
            if (node.has("data") && node.get("data").has("content")) {
                return node.get("data").get("content").asText();
            }
            if (node.has("result")) return node.get("result").asText();
            if (node.has("text")) return node.get("text").asText();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private String toJson(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
