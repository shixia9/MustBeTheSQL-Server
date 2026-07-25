package com.sql.logic.engine.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agentic.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * REST controller for workflow CRUD and execution.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowRepository repository;
    private final WorkflowEngine engine;
    private final NodeRegistry nodeRegistry;
    private final ObjectMapper objectMapper;

    public WorkflowController(WorkflowRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.nodeRegistry = new NodeRegistry();
        this.engine = new WorkflowEngine((node, input) ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        "{\"nodeId\":\"" + node.getId() + "\",\"type\":\"" + node.getType()
                        + "\",\"title\":\"" + (node.getData() != null ? node.getData().getTitle() : "") + "\"}"
                ));
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
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> createWorkflow(@RequestBody WorkflowDefinition def) {
        String id = repository.save(def);
        return ResponseEntity.ok(Map.of("id", id, "name", def.getName()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateWorkflow(@PathVariable String id, @RequestBody WorkflowDefinition def) {
        if (repository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repository.update(id, def);
        return ResponseEntity.ok(Map.of("id", id, "name", def.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable String id) {
        repository.delete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/{id}/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> executeWorkflow(@PathVariable String id) {
        Optional<WorkflowDefinition> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return Flux.just("{\"type\":\"ERROR\",\"message\":\"Workflow not found: " + id + "\"}");
        }

        WorkflowDefinition def = opt.get();
        log.info("[Workflow] Executing workflow: id={}, name={}, nodes={}, edges={}",
                id, def.getName(), def.getNodes().size(), def.getEdges().size());

        return Flux.create(sink -> {
            engine.execute(def, Map.of())
                    .thenAccept(result -> {
                        try {
                            if (result.success()) {
                                Map<String, Object> event = new LinkedHashMap<>();
                                event.put("type", "WORKFLOW_COMPLETED");
                                event.put("nodeOutputs", result.nodeOutputs());
                                sink.next(objectMapper.writeValueAsString(event));
                            } else {
                                sink.next("{\"type\":\"ERROR\",\"message\":\"" + result.errorMessage() + "\"}");
                            }
                            sink.complete();
                        } catch (Exception e) {
                            sink.error(e);
                        }
                    })
                    .exceptionally(e -> {
                        sink.next("{\"type\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}");
                        sink.complete();
                        return null;
                    });
        });
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportWorkflow(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, String>> importWorkflow(@RequestBody WorkflowDefinition def) {
        String id = repository.save(def);
        return ResponseEntity.ok(Map.of("id", id, "name", def.getName()));
    }
}
