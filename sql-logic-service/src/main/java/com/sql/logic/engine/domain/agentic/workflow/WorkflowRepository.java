package com.sql.logic.engine.domain.agentic.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.infrastructure.dao.WorkflowDefinitionDao;
import com.sql.logic.engine.infrastructure.po.WorkflowDefinitionPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Database-backed workflow repository using the workflow_definition table.
 * Replaces the previous in-memory ConcurrentHashMap implementation.
 */
@Component
public class WorkflowRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRepository.class);
    private final WorkflowDefinitionDao dao;
    private final ObjectMapper objectMapper;

    public WorkflowRepository(WorkflowDefinitionDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a new workflow for the given user.
     */
    public String save(WorkflowDefinition def, Long userId, Long workspaceId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String json = toJson(def);
        WorkflowDefinitionPO po = new WorkflowDefinitionPO();
        po.setId(id);
        po.setName(def.getName() != null ? def.getName() : "Untitled");
        po.setDescription(def.getDescription());
        po.setUserId(userId);
        po.setWorkspaceId(workspaceId);
        po.setVersion(def.getVersion() != null ? def.getVersion() : "1.0");
        po.setConfigJson(json);
        po.setStatus("DRAFT");
        dao.insert(po);
        log.info("Saved workflow id={}, name={}, userId={}", id, po.getName(), userId);
        return id;
    }

    /**
     * Update an existing workflow. Only the owner can update.
     */
    public void update(String id, WorkflowDefinition def, Long userId) {
        WorkflowDefinitionPO po = dao.selectById(id);
        if (po == null) throw new IllegalArgumentException("Workflow not found: " + id);
        if (!po.getUserId().equals(userId))
            throw new SecurityException("User " + userId + " does not own workflow " + id);

        String json = toJson(def);
        po.setName(def.getName() != null ? def.getName() : po.getName());
        po.setDescription(def.getDescription());
        po.setVersion(def.getVersion() != null ? def.getVersion() : po.getVersion());
        po.setConfigJson(json);
        dao.updateById(po);
        log.info("Updated workflow id={}, userId={}", id, userId);
    }

    /**
     * Delete a workflow. Only the owner can delete.
     */
    public void delete(String id, Long userId) {
        WorkflowDefinitionPO po = dao.selectById(id);
        if (po == null) return;
        if (!po.getUserId().equals(userId))
            throw new SecurityException("User " + userId + " does not own workflow " + id);
        dao.deleteById(id);
        log.info("Deleted workflow id={}, userId={}", id, userId);
    }

    /**
     * Find a workflow by ID and deserialize into a WorkflowDefinition.
     */
    public Optional<WorkflowDefinition> findById(String id) {
        WorkflowDefinitionPO po = dao.selectById(id);
        if (po == null) return Optional.empty();
        return Optional.of(fromJson(po.getConfigJson()));
    }

    /**
     * List all workflows for a specific user.
     */
    public List<Map<String, String>> listAll(Long userId) {
        var query = new LambdaQueryWrapper<WorkflowDefinitionPO>()
                .eq(WorkflowDefinitionPO::getUserId, userId)
                .orderByDesc(WorkflowDefinitionPO::getUpdateTime);
        List<WorkflowDefinitionPO> list = dao.selectList(query);
        List<Map<String, String>> result = new ArrayList<>();
        for (var po : list) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", po.getId());
            item.put("name", po.getName());
            result.add(item);
        }
        return result;
    }

    // --- JSON helpers ---

    private String toJson(WorkflowDefinition def) {
        try {
            return objectMapper.writeValueAsString(def);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize workflow", e);
        }
    }

    private WorkflowDefinition fromJson(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize workflow", e);
        }
    }
}
