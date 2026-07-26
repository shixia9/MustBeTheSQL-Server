package com.sql.logic.engine.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.AgenticRunner;
import com.sql.logic.engine.domain.agentic.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowRepository repository;
    private final NodeRegistry nodeRegistry;
    private final ObjectMapper objectMapper;
    private final AgenticRunner agenticRunner;

    public WorkflowController(WorkflowRepository repository, ObjectMapper objectMapper,
                               AgenticRunner agenticRunner) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.agenticRunner = agenticRunner;
        this.nodeRegistry = new NodeRegistry();
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<Map<String, Object>>> getNodeTypes() {
        return ResponseEntity.ok(nodeRegistry.getNodeTypes());
    }

    @GetMapping
    public ResponseEntity<List<Map<String, String>>> listWorkflows() {
        return ResponseEntity.ok(repository.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getWorkflow(@PathVariable String id) {
        return repository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> createWorkflow(@RequestBody WorkflowDefinition def) {
        String id = repository.save(def);
        return ResponseEntity.ok(Map.of("id", id, "name", def.getName()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateWorkflow(@PathVariable String id, @RequestBody WorkflowDefinition def) {
        if (repository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        repository.update(id, def);
        return ResponseEntity.ok(Map.of("id", id, "name", def.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable String id) {
        repository.delete(id);
        return ResponseEntity.ok().build();
    }

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

        // Extract agent names from flow nodes
        List<String> agentNames = def.getNodes().stream()
                .filter(n -> "agent".equals(n.getType()) && n.getData() != null && n.getData().getAgentName() != null)
                .map(n -> n.getData().getAgentName())
                .distinct().toList();

        log.info("[Workflow] Executing flow '{}' with agents={}, input='{}'",
                def.getName(), agentNames, userInput);

        try {
            AgenticRunner.AgentRunHandle handle = agenticRunner.execute(
                    connectionId != null ? connectionId : 1L,
                    userInput,
                    userId != null ? userId : 1L,
                    llmConfigId != null ? llmConfigId : 0L,
                    null, null, "", true);

            return handle.getUnifiedSseFlux()
                    .concatWith(Flux.just("{\"type\":\"WORKFLOW_COMPLETED\"}"))
                    .onErrorResume(e -> Flux.just(
                            "{\"type\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}"));
        } catch (Exception e) {
            log.error("[Workflow] Execution failed", e);
            return Flux.just("{\"type\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportWorkflow(@PathVariable String id) {
        return repository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, String>> importWorkflow(@RequestBody WorkflowDefinition def) {
        String id = repository.save(def);
        return ResponseEntity.ok(Map.of("id", id, "name", def.getName()));
    }

    private Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s); } catch (Exception ignored) {} }
        return null;
    }
}
