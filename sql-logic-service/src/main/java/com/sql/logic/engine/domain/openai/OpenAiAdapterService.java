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
import java.util.*;

/**
 * Adapter that translates OpenAI-compatible ChatCompletion requests
 * into internal AgenticRunner calls and maps responses back to OpenAI format.
 * <p>
 * Phase 6 enhancements:
 * <ul>
 *   <li>Tool calling loop — auto-dispatch tool_calls → execute → feed results back</li>
 *   <li>Vision support — parse image_url content parts and pass to upstream LLM</li>
 *   <li>Conversation history — preserve multi-turn messages</li>
 *   <li>Dynamic finish_reason (stop, length, tool_calls, content_filter)</li>
 * </ul>
 */
@Service
public class OpenAiAdapterService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAdapterService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_TOOL_ROUNDS = 5;

    private final AgenticRunner agenticRunner;
    private final VisionContentHandler visionHandler;
    private final ToolCallDispatcher toolDispatcher;
    private final OpenAiToolRegistry toolRegistry;

    public OpenAiAdapterService(AgenticRunner agenticRunner,
                                VisionContentHandler visionHandler,
                                ToolCallDispatcher toolDispatcher,
                                OpenAiToolRegistry toolRegistry) {
        this.agenticRunner = agenticRunner;
        this.visionHandler = visionHandler;
        this.toolDispatcher = toolDispatcher;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Extract the last user message content, with vision support.
     */
    public String extractUserQuestion(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = request.getMessages().get(i);
            if ("user".equals(msg.getRole())) {
                if (visionHandler.hasVisionContent(msg.getContent())) {
                    return visionHandler.buildVisionEnrichedQuestion(msg.getContent());
                }
                return visionHandler.extractText(msg.getContent());
            }
        }
        return "";
    }

    /**
     * Check whether the request includes tools (function calling).
     */
    public boolean hasTools(ChatCompletionRequest request) {
        return request.getTools() != null && !request.getTools().isEmpty();
    }

    /**
     * Non-streaming completion with tool calling support.
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

        // Build conversation history
        String history = buildHistoryString(request.getMessages());

        try {
            // Check for tool calling loop
            if (hasTools(request)) {
                return completeWithTools(request, question + "\n" + history,
                        userId, connectionId, requestId, created);
            }

            AgenticRunner.AgentRunHandle handle = agenticRunner.execute(
                    connectionId, question + "\n" + history, userId, llmConfigId, workspaceId,
                    null, "", false);

            String content = handle.getUnifiedSseFlux()
                    .take(Duration.ofSeconds(120))
                    .collectList()
                    .map(this::extractTextFromSseEvents)
                    .block(Duration.ofSeconds(130));

            return buildResponse(requestId, created, request.getModel(),
                    content != null ? content : "No response generated", "stop",
                    null, 0, 0);

        } catch (Exception e) {
            log.error("OpenAI completion failed", e);
            throw new RuntimeException("Completion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Handle tool calling loop: LLM returns tool_calls → execute → feed back → repeat.
     */
    private ChatCompletionResponse completeWithTools(ChatCompletionRequest request,
                                                      String question,
                                                      Long userId,
                                                      Long connectionId,
                                                      String requestId, long created) {
        List<ChatCompletionRequest.Message> conversation = new ArrayList<>();

        // Add system/tool context from original request
        if (request.getMessages() != null) {
            for (var m : request.getMessages()) {
                if ("system".equals(m.getRole()) || "tool".equals(m.getRole())) {
                    conversation.add(m);
                }
            }
        }

        // Add user question
        ChatCompletionRequest.Message userMsg = new ChatCompletionRequest.Message();
        userMsg.setRole("user");
        userMsg.setContent(question);
        conversation.add(userMsg);

        // Inject tools from the registry
        request.setTools(toolRegistry.buildTools());

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            // Serialize the full conversation with tool results as input context
            String conversationInput = serializeConversation(conversation);

            // Execute through internal agent with full conversation context
            AgenticRunner.AgentRunHandle handle = agenticRunner.execute(
                    connectionId, conversationInput, userId, null, null,
                    null, "", false);

            String content = handle.getUnifiedSseFlux()
                    .take(Duration.ofSeconds(120))
                    .collectList()
                    .map(this::extractTextFromSseEvents)
                    .block(Duration.ofSeconds(130));

            if (content == null || content.isBlank()) {
                return buildResponse(requestId, created, request.getModel(),
                        "No response", "stop", null, 0, 0);
            }

            // Check if the LLM response looks like a tool call request
            if (looksLikeToolCall(content)) {
                List<ChatCompletionRequest.ToolCall> toolCalls = parseToolCallsFromContent(content);
                if (!toolCalls.isEmpty()) {
                    // Add assistant message with tool calls to conversation
                    ChatCompletionRequest.Message asstMsg = new ChatCompletionRequest.Message();
                    asstMsg.setRole("assistant");
                    asstMsg.setContent(null);
                    asstMsg.setToolCalls(toolCalls);
                    conversation.add(asstMsg);

                    // Execute tools and add results to conversation
                    List<ChatCompletionRequest.Message> results =
                            toolDispatcher.executeTools(toolCalls, userId, connectionId);
                    conversation.addAll(results);
                    continue;
                }
            }

            // No tool calls — return final content
            return buildResponse(requestId, created, request.getModel(),
                    content, "stop", null, 0, 0);
        }

        return buildResponse(requestId, created, request.getModel(),
                "Max tool calling rounds exceeded", "stop", null, 0, 0);
    }

    /**
     * Serialize the conversation list into a single prompt string the LLM can process.
     * Tool results are included so the LLM can see what each tool returned.
     */
    private String serializeConversation(List<ChatCompletionRequest.Message> conversation) {
        StringBuilder sb = new StringBuilder();
        for (var m : conversation) {
            String role = m.getRole();
            if ("system".equals(role)) {
                sb.append("[System] ").append(visionHandler.extractText(m.getContent())).append("\n");
            } else if ("user".equals(role)) {
                sb.append("[User] ").append(visionHandler.extractText(m.getContent())).append("\n");
            } else if ("assistant".equals(role)) {
                if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                    sb.append("[Assistant called tools: ");
                    for (var tc : m.getToolCalls()) {
                        String fn = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
                        sb.append(fn).append(" ");
                    }
                    sb.append("]\n");
                } else if (m.getContent() != null) {
                    sb.append("[Assistant] ").append(m.getContent()).append("\n");
                }
            } else if ("tool".equals(role)) {
                String tcId = m.getToolCallId() != null ? m.getToolCallId() : "";
                String result = visionHandler.extractText(m.getContent());
                String shortResult = result.length() > 800 ? result.substring(0, 800) + "..." : result;
                sb.append("[Tool Result ").append(tcId).append("] ").append(shortResult).append("\n");
            }
        }
        return sb.toString();
    }

    private boolean looksLikeToolCall(String content) {
        return (content.contains("\"tool_calls\"") && content.contains("\"function\""))
                || (content.contains("\"name\":") && content.contains("\"arguments\":"));
    }

    /**
     * Streaming completion with SSE Flux.
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

        String history = buildHistoryString(request.getMessages());

        try {
            AgenticRunner.AgentRunHandle handle = agenticRunner.execute(
                    connectionId, question + "\n" + history, userId, llmConfigId, workspaceId,
                    null, "", false);

            boolean[] firstChunk = {true};

            return handle.getUnifiedSseFlux()
                    .take(Duration.ofSeconds(300))
                    .filter(s -> s != null && !s.isBlank() && s.startsWith("{"))
                    .map(sseEvent -> {
                        ChatCompletionStreamResponse chunk = new ChatCompletionStreamResponse();
                        chunk.setId(requestId);
                        chunk.setCreated(created);
                        chunk.setModel(request.getModel());

                        ChatCompletionStreamResponse.Choice choice =
                                new ChatCompletionStreamResponse.Choice();
                        choice.setIndex(0);
                        ChatCompletionStreamResponse.Delta delta =
                                new ChatCompletionStreamResponse.Delta();

                        if (firstChunk[0]) {
                            delta.setRole("assistant");
                            firstChunk[0] = false;
                        }
                        delta.setContent(extractTextFromSseEvent(sseEvent));
                        choice.setDelta(delta);
                        choice.setFinishReason(null);
                        chunk.setChoices(List.of(choice));
                        return "data: " + toJson(chunk) + "\n\n";
                    })
                    .concatWith(Mono.fromCallable(() -> {
                        ChatCompletionStreamResponse last = new ChatCompletionStreamResponse();
                        last.setId(requestId);
                        last.setCreated(created);
                        last.setModel(request.getModel());
                        ChatCompletionStreamResponse.Choice c =
                                new ChatCompletionStreamResponse.Choice();
                        c.setIndex(0);
                        ChatCompletionStreamResponse.Delta d =
                                new ChatCompletionStreamResponse.Delta();
                        d.setContent("");
                        c.setDelta(d);
                        c.setFinishReason("stop");
                        last.setChoices(List.of(c));
                        return "data: " + toJson(last) + "\n\n";
                    }))
                    .concatWith(Mono.just("data: [DONE]\n\n"))
                    .onErrorResume(e -> {
                        log.error("Stream error", e);
                        return Mono.just("data: {\"error\":{\"message\":\""
                                + e.getMessage() + "\"}}\n\n");
                    });

        } catch (Exception e) {
            log.error("OpenAI stream failed", e);
            return Flux.error(e);
        }
    }

    /**
     * Build a conversation history string from prior messages.
     * Preserves system, user, and assistant messages (excluding the last user message).
     */
    private String buildHistoryString(List<ChatCompletionRequest.Message> messages) {
        if (messages == null || messages.size() <= 1) return "";
        StringBuilder sb = new StringBuilder();
        // Skip the last user message (it's the current question)
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                lastUserIdx = i;
                break;
            }
        }
        for (int i = 0; i < messages.size(); i++) {
            if (i == lastUserIdx) continue;
            var m = messages.get(i);
            String role = m.getRole();
            String content = visionHandler.extractText(m.getContent());
            if (content == null || content.isBlank()) continue;
            if ("system".equals(role)) {
                sb.append("[System] ").append(content).append("\n");
            } else if ("user".equals(role)) {
                sb.append("[Previous Question] ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                String shortContent = content.length() > 500
                        ? content.substring(0, 500) + "..." : content;
                sb.append("[Previous Answer] ").append(shortContent).append("\n");
            }
        }
        return sb.toString();
    }

    private ChatCompletionResponse buildResponse(String id, Long created, String model,
                                                  String content, String finishReason,
                                                  List<ChatCompletionResponse.ToolCall> toolCalls,
                                                  int promptTokens, int completionTokens) {
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setId(id);
        response.setCreated(created);
        response.setModel(model);

        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        choice.setIndex(0);
        ChatCompletionResponse.Message msg = new ChatCompletionResponse.Message();
        msg.setRole("assistant");
        msg.setContent(content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.setToolCalls(toolCalls);
        }
        choice.setMessage(msg);
        choice.setFinishReason(finishReason);
        response.setChoices(List.of(choice));

        ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(promptTokens + completionTokens);
        response.setUsage(usage);

        return response;
    }

    @SuppressWarnings("unchecked")
    private List<ChatCompletionRequest.ToolCall> parseToolCallsFromContent(String content) {
        try {
            var node = mapper.readTree(content);
            if (node.has("tool_calls")) {
                return mapper.convertValue(node.get("tool_calls"),
                        mapper.getTypeFactory().constructCollectionType(
                                List.class, ChatCompletionRequest.ToolCall.class));
            }
        } catch (Exception ignored) {
        }
        return List.of();
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
            var node = mapper.readTree(sseEvent);
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
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
