package com.sql.logic.engine.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.infrastructure.dao.AgentVersionDao;
import com.sql.logic.engine.infrastructure.po.AgentEntity;
import com.sql.logic.engine.infrastructure.po.AgentVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Agent version management.
 * <p>
 * Supports manual publish (user clicks "Publish Version"), listing version history,
 * reverting to a previous version, and auto-cleanup of versions older than 7 days.
 */
@Service
public class AgentVersionAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentVersionAppService.class);

    private final AgentVersionDao agentVersionDao;
    private final AgentEntityAppService agentEntityAppService;
    private final ObjectMapper objectMapper;

    public AgentVersionAppService(AgentVersionDao agentVersionDao,
                                  AgentEntityAppService agentEntityAppService,
                                  ObjectMapper objectMapper) {
        this.agentVersionDao = agentVersionDao;
        this.agentEntityAppService = agentEntityAppService;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish a new version snapshot for the given agent.
     * Auto-deletes versions older than 7 days (keeping at least 1).
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentVersion publish(Long agentId, Long userId) {
        AgentEntity entity = agentEntityAppService.getRawEntity(agentId, userId);
        if (entity == null) {
            throw new IllegalArgumentException("Agent not found or access denied");
        }

        int nextVersion = agentVersionDao.maxVersionNumber(agentId) + 1;
        String snapshot;
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("name", entity.getName());
            snap.put("description", entity.getDescription());
            snap.put("avatar", entity.getAvatar());
            snap.put("systemPrompt", entity.getSystemPrompt());
            snap.put("welcomeMessage", entity.getWelcomeMessage());
            snap.put("toolsConfig", entity.getToolsConfig());
            snap.put("ragConfig", entity.getRagConfig());
            snap.put("memoryEnabled", entity.getMemoryEnabled());
            snap.put("isDefault", entity.getIsDefault());
            snapshot = objectMapper.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise agent snapshot", e);
        }

        AgentVersion v = new AgentVersion();
        v.setAgentId(agentId);
        v.setVersionNumber(nextVersion);
        v.setSnapshotJson(snapshot);
        v.setPublishedBy(userId);
        v.setPublishTime(new Date());
        agentVersionDao.insert(v);

        // Cleanup versions older than 7 days, keeping at least 1
        cleanupOldVersions(agentId);

        log.info("[AgentVersionAppService] Published version {} for agent id={}", nextVersion, agentId);
        return v;
    }

    /** List version history for an agent, newest first. */
    public List<Map<String, Object>> listVersions(Long agentId, Long userId) {
        // verify access
        if (agentEntityAppService.getById(agentId, userId) == null) {
            throw new IllegalArgumentException("Agent not found or access denied");
        }
        List<AgentVersion> versions = agentVersionDao.listByAgentId(agentId);
        return versions.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("agentId", v.getAgentId());
            m.put("versionNumber", v.getVersionNumber());
            m.put("publishedBy", v.getPublishedBy());
            m.put("publishTime", fmt(v.getPublishTime()));
            return m;
        }).toList();
    }

    /** Get a specific version's snapshot for preview/revert. */
    public String getSnapshot(Long versionId, Long agentId, Long userId) {
        if (agentEntityAppService.getById(agentId, userId) == null) {
            throw new IllegalArgumentException("Agent not found or access denied");
        }
        AgentVersion v = agentVersionDao.selectById(versionId);
        if (v == null || !v.getAgentId().equals(agentId)) {
            return null;
        }
        return v.getSnapshotJson();
    }

    /** Revert agent config to a published version. */
    @Transactional(rollbackFor = Exception.class)
    public void revert(Long versionId, Long agentId, Long userId) {
        String snapshot = getSnapshot(versionId, agentId, userId);
        if (snapshot == null) {
            throw new IllegalArgumentException("Version not found");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = objectMapper.readValue(snapshot, Map.class);
            agentEntityAppService.revertToSnapshot(agentId, userId, snap);
            log.info("[AgentVersionAppService] Reverted agent id={} to version id={}", agentId, versionId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse version snapshot", e);
        }
    }

    /** Delete a specific version. Requires agent ownership or workspace membership. */
    public void deleteVersion(Long versionId, Long agentId, Long userId) {
        if (agentEntityAppService.getRawEntity(agentId, userId) == null) {
            throw new IllegalArgumentException("Agent not found or access denied");
        }
        int deleted = agentVersionDao.deleteByIdAndAgent(versionId, agentId);
        if (deleted > 0) {
            log.info("[AgentVersionAppService] Deleted version id={} from agent id={}", versionId, agentId);
        }
    }

    private void cleanupOldVersions(Long agentId) {
        Date cutoff = new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
        int deleted = agentVersionDao.deleteOlderThan(agentId, cutoff);
        if (deleted > 0) {
            log.info("[AgentVersionAppService] Cleaned up {} old versions for agent id={}", deleted, agentId);
        }
    }

    private String fmt(Date d) {
        return d == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }
}
