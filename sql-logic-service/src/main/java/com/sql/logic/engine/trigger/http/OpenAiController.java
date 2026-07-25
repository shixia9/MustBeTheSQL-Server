package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.domain.openai.OpenAiAdapterService;
import com.sql.logic.engine.trigger.http.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * OpenAI-compatible API endpoints.
 * <p>
 * Supports external clients connecting via OpenAI Python/JS SDK.
 * Authentication via Bearer token (validated by {@link OpenAiAuthFilter}).
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    private final OpenAiAdapterService adapterService;

    public OpenAiController(OpenAiAdapterService adapterService) {
        this.adapterService = adapterService;
    }

    /**
     * Chat Completions — mirrors POST /v1/chat/completions.
     * Supports both streaming (stream=true) and non-streaming.
     */
    @PostMapping(value = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object chatCompletions(@RequestBody ChatCompletionRequest request,
                                   HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("openai_userId");
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(ErrorResponse.of("Invalid API key", "invalid_request_error", "invalid_api_key"));
        }

        log.info("[OpenAI] Chat completion request: model={}, stream={}, userId={}",
                request.getModel(), request.getStream(), userId);

        boolean stream = request.getStream() != null && request.getStream();

        if (stream) {
            return streamComplete(request, userId);
        }
        return completeSync(request, userId);
    }

    private Flux<String> streamComplete(ChatCompletionRequest request, Long userId) {
        return adapterService.streamComplete(request, userId, null, null, null);
    }

    private ResponseEntity<?> completeSync(ChatCompletionRequest request, Long userId) {
        try {
            ChatCompletionResponse response = adapterService.complete(request, userId, null, null, null);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of(e.getMessage(), "invalid_request_error", "invalid_request"));
        } catch (Exception e) {
            log.error("[OpenAI] Completion error", e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("Internal error: " + e.getMessage(), "server_error", "internal_error"));
        }
    }

    /**
     * List Models — mirrors GET /v1/models.
     */
    @GetMapping("/models")
    public ResponseEntity<ModelListResponse> listModels() {
        long created = System.currentTimeMillis() / 1000;
        ModelListResponse response = new ModelListResponse();
        response.setData(List.of(
                model("claude-sonnet-4-6", created, "anthropic"),
                model("claude-opus-4-7", created, "anthropic"),
                model("gpt-4o", created, "openai"),
                model("gpt-4o-mini", created, "openai")
        ));
        return ResponseEntity.ok(response);
    }

    private static ModelListResponse.Model model(String id, Long created, String ownedBy) {
        ModelListResponse.Model m = new ModelListResponse.Model();
        m.setId(id);
        m.setCreated(created);
        m.setOwnedBy(ownedBy);
        return m;
    }
}
