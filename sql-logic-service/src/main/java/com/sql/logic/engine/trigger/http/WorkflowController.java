package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.agentic.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowRepository repository;
    private final NodeRegistry nodeRegistry;
    private final WorkflowEngine.WorkflowAgentExecutor workflowAgentExecutor;

    public WorkflowController(WorkflowRepository repository,
                               WorkflowEngine.WorkflowAgentExecutor workflowAgentExecutor) {
        this.repository = repository;
        this.workflowAgentExecutor = workflowAgentExecutor;
        this.nodeRegistry = new NodeRegistry();
    }

    // --- Auth helper ---

    private Long currentUserId() {
        try {
            String id = (String) StpUtil.getLoginId();
            if (id == null || !id.matches("\\d+")) return null;
            return Long.valueOf(id);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Endpoints ---

    @GetMapping("/nodes")
    public ResponseEntity<Result<List<Map<String, Object>>>> getNodeTypes() {
        return ResponseEntity.ok(Result.success(nodeRegistry.getNodeTypes()));
    }

    @GetMapping
    public ResponseEntity<Result<List<Map<String, String>>>> listWorkflows() {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.ok(Result.success(List.of()));
        return ResponseEntity.ok(Result.success(repository.listAll(userId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<WorkflowDefinition>> getWorkflow(@PathVariable String id) {
        return repository.findById(id)
                .map(def -> ResponseEntity.ok(Result.success(def)))
                .orElse(ResponseEntity.ok(Result.error(404, "Workflow not found: " + id)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result<Map<String, String>>> createWorkflow(@RequestBody WorkflowDefinition def) {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.ok(Result.error(401, "Login required"));
        Long workspaceId = extractWorkspaceId(def);
        String id = repository.save(def, userId, workspaceId);
        return ResponseEntity.ok(Result.success(Map.of("id", id, "name", def.getName())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<Map<String, String>>> updateWorkflow(@PathVariable String id,
                                                                       @RequestBody WorkflowDefinition def) {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.ok(Result.error(401, "Login required"));
        try {
            repository.update(id, def, userId);
            return ResponseEntity.ok(Result.success(Map.of("id", id, "name", def.getName())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Result.error(404, e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.ok(Result.error(403, e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> deleteWorkflow(@PathVariable String id) {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.ok(Result.error(401, "Login required"));
        try {
            repository.delete(id, userId);
            return ResponseEntity.ok(Result.success(null));
        } catch (SecurityException e) {
            return ResponseEntity.ok(Result.error(403, e.getMessage()));
        }
    }

    /**
     * Execute a workflow by compiling the user's custom DAG and running it
     * through the WorkflowEngine with per-node SSE streaming.
     */
    @PostMapping(value = "/{id}/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> executeWorkflow(@PathVariable String id,
                                         @RequestBody Map<String, Object> params) {
        var opt = repository.findById(id);
        if (opt.isEmpty()) {
            return Flux.just("{\"type\":\"ERROR\",\"message\":\"Workflow not found: " + id + "\"}");
        }

        WorkflowDefinition def = opt.get();
        String userInput = (String) params.getOrDefault("userInput", "");
        Long connectionId = toLong(params.get("connectionId"));
        Long userId = toLong(params.get("userId"));
        Long llmConfigId = toLong(params.get("llmConfigId"));

        if (userInput == null || userInput.isBlank()) {
            return Flux.just("{\"type\":\"ERROR\",\"message\":\"userInput is required\"}");
        }

        // Extract agent names for logging
        List<String> agentNames = def.getNodes().stream()
                .filter(n -> "agent".equals(n.getType()) && n.getData() != null && n.getData().getAgentName() != null)
                .map(n -> n.getData().getAgentName())
                .distinct().toList();

        log.info("[Workflow] Executing flow '{}' id={} with agents={}, input='{}'",
                def.getName(), id, agentNames, userInput);

        // Build input context
        Map<String, Object> inputContext = new LinkedHashMap<>();
        inputContext.put("userInput", userInput);
        if (connectionId != null) inputContext.put("connectionId", connectionId);
        if (userId != null) inputContext.put("userId", userId);
        if (llmConfigId != null) inputContext.put("llmConfigId", llmConfigId);
        inputContext.put("threadId", UUID.randomUUID().toString());

        // Create SSE sink for per-node events
        Sinks.Many<String> eventSink = Sinks.many().multicast().onBackpressureBuffer();

        // Build the engine with event callback
        WorkflowEngine engine = new WorkflowEngine(workflowAgentExecutor);
        engine.execute(def, inputContext, event -> {
            String json = event.toJson();
            log.debug("[Workflow] Event: {}", json);
            eventSink.tryEmitNext(json);
        }).thenAccept(result -> {
            if (result.success()) {
                log.info("[Workflow] Flow '{}' completed with {} node outputs",
                        def.getName(), result.nodeOutputs().size());
            } else {
                log.error("[Workflow] Flow '{}' failed: {}", def.getName(), result.errorMessage());
            }
            eventSink.tryEmitComplete();
        }).exceptionally(e -> {
            log.error("[Workflow] Flow '{}' execution error", def.getName(), e);
            eventSink.tryEmitNext("{\"type\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}");
            eventSink.tryEmitComplete();
            return null;
        });

        return eventSink.asFlux();
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<Result<WorkflowDefinition>> exportWorkflow(@PathVariable String id) {
        return repository.findById(id)
                .map(def -> ResponseEntity.ok(Result.success(def)))
                .orElse(ResponseEntity.ok(Result.error(404, "Workflow not found: " + id)));
    }

    @PostMapping("/import")
    public ResponseEntity<Result<Map<String, String>>> importWorkflow(@RequestBody WorkflowDefinition def) {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.ok(Result.error(401, "Login required"));
        Long workspaceId = extractWorkspaceId(def);
        String id = repository.save(def, userId, workspaceId);
        return ResponseEntity.ok(Result.success(Map.of("id", id, "name", def.getName())));
    }

    // --- Helpers ---

    private Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s); } catch (Exception ignored) {} }
        return null;
    }

    /**
     * Extract workspaceId from the workflow definition if present.
     * The frontend may pass it as a node data field or we use the user's default workspace.
     */
    private Long extractWorkspaceId(WorkflowDefinition def) {
        // For now, return null (no workspace scoping). Future: read from def context or session.
        return null;
    }
}
