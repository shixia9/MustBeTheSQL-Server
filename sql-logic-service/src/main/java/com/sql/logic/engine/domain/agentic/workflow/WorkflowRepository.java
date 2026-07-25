package com.sql.logic.engine.domain.agentic.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory workflow repository.
 * Stores workflow definitions as JSON strings keyed by workflow ID.
 */
@Component
public class WorkflowRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRepository.class);
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, WorkflowDefinition> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> names = new ConcurrentHashMap<>();

    public WorkflowRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String save(WorkflowDefinition def) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        store.put(id, def);
        names.put(id, def.getName());
        log.info("Saved workflow id={}, name={}", id, def.getName());
        return id;
    }

    public void update(String id, WorkflowDefinition def) {
        store.put(id, def);
        names.put(id, def.getName());
        log.info("Updated workflow id={}", id);
    }

    public void delete(String id) {
        store.remove(id);
        names.remove(id);
        log.info("Deleted workflow id={}", id);
    }

    public Optional<WorkflowDefinition> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Map<String, String>> listAll() {
        List<Map<String, String>> result = new ArrayList<>();
        for (var entry : names.entrySet()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("name", entry.getValue());
            result.add(item);
        }
        return result;
    }
}
