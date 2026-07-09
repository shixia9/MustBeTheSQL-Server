package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.common.dto.AgentEntityRequest;
import com.sql.logic.engine.common.dto.AgentEntityResponse;
import com.sql.logic.engine.infrastructure.dao.AgentEntityDao;
import com.sql.logic.engine.infrastructure.po.AgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase B (B4) Agent Studio application service.
 * <p>
 * CRUD over {@code agent_entity} plus default-Agent election (only one default per user).
 * JSON config columns ({@code tools_config}, {@code rag_config}) are serialised from the
 * structured request fields so the frontend never has to hand-build JSON.
 */
@Service
public class AgentEntityAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentEntityAppService.class);
    private static final List<String> ALL_TOOLS = List.of("sql", "schema", "python", "sample");

    private final AgentEntityDao agentEntityDao;
    private final ObjectMapper objectMapper;

    public AgentEntityAppService(AgentEntityDao agentEntityDao, ObjectMapper objectMapper) {
        this.agentEntityDao = agentEntityDao;
        this.objectMapper = objectMapper;
    }

    public List<AgentEntityResponse> listByUser(Long userId, Long workspaceId) {
        QueryWrapper<AgentEntity> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (workspaceId != null) {
            qw.and(w -> w.isNull("workspace_id").or().eq("workspace_id", workspaceId));
        }
        qw.orderByDesc("is_default").orderByDesc("update_time");
        return agentEntityDao.selectList(qw).stream().map(this::toResponse).toList();
    }

    public AgentEntityResponse getById(Long id, Long userId) {
        AgentEntity e = owned(id, userId);
        return e == null ? null : toResponse(e);
    }

    /** Return the user's default Agent, or the first one, or null if none exist. */
    public AgentEntity findDefaultForUser(Long userId) {
        QueryWrapper<AgentEntity> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("status", 1).orderByDesc("is_default").orderByDesc("update_time").last("LIMIT 1");
        return agentEntityDao.selectOne(qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentEntityResponse create(Long userId, Long workspaceId, AgentEntityRequest req) {
        AgentEntity e = new AgentEntity();
        e.setUserId(userId);
        e.setWorkspaceId(workspaceId);
        applyRequest(e, req);
        e.setStatus(1);
        e.setMemoryEnabled(req.getMemoryEnabled() == null || req.getMemoryEnabled() ? 1 : 0);
        e.setIsDefault(Boolean.TRUE.equals(req.getIsDefault()) ? 1 : 0);
        Date now = new Date();
        e.setCreateTime(now);
        e.setUpdateTime(now);
        agentEntityDao.insert(e);
        if (e.getIsDefault() != null && e.getIsDefault() == 1) {
            clearOtherDefaults(userId, e.getId());
        }
        log.info("[AgentEntityAppService] Created agent id={}, name='{}' for userId={}", e.getId(), e.getName(), userId);
        return toResponse(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentEntityResponse update(Long id, Long userId, AgentEntityRequest req) {
        AgentEntity e = owned(id, userId);
        if (e == null) {
            return null;
        }
        applyRequest(e, req);
        if (req.getMemoryEnabled() != null) {
            e.setMemoryEnabled(req.getMemoryEnabled() ? 1 : 0);
        }
        if (req.getIsDefault() != null) {
            e.setIsDefault(req.getIsDefault() ? 1 : 0);
        }
        e.setUpdateTime(new Date());
        agentEntityDao.updateById(e);
        if (e.getIsDefault() != null && e.getIsDefault() == 1) {
            clearOtherDefaults(userId, e.getId());
        }
        return toResponse(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id, Long userId) {
        AgentEntity e = owned(id, userId);
        if (e == null) return false;
        agentEntityDao.deleteById(id);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean setDefault(Long id, Long userId) {
        AgentEntity e = owned(id, userId);
        if (e == null) return false;
        UpdateWrapper<AgentEntity> uw = new UpdateWrapper<>();
        uw.eq("id", id).set("is_default", 1).set("update_time", new Date());
        agentEntityDao.update(null, uw);
        clearOtherDefaults(userId, id);
        return true;
    }

    private void clearOtherDefaults(Long userId, Long keepId) {
        UpdateWrapper<AgentEntity> uw = new UpdateWrapper<>();
        uw.eq("user_id", userId).ne("id", keepId).set("is_default", 0);
        agentEntityDao.update(null, uw);
    }

    private AgentEntity owned(Long id, Long userId) {
        AgentEntity e = agentEntityDao.selectById(id);
        if (e == null || (userId != null && !userId.equals(e.getUserId()))) {
            return null;
        }
        return e;
    }

    private void applyRequest(AgentEntity e, AgentEntityRequest req) {
        if (req.getName() != null) e.setName(req.getName());
        if (req.getDescription() != null) e.setDescription(req.getDescription());
        if (req.getAvatar() != null) e.setAvatar(req.getAvatar());
        if (req.getSystemPrompt() != null) e.setSystemPrompt(req.getSystemPrompt());
        if (req.getWelcomeMessage() != null) e.setWelcomeMessage(req.getWelcomeMessage());
        e.setToolsConfig(buildToolsJson(req.getEnabledTools()));
        e.setRagConfig(buildRagJson(req.getTopK(), req.getScoreThreshold(), req.getRagEnabled()));
    }

    private String buildToolsJson(List<String> enabledTools) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String t : ALL_TOOLS) {
            map.put(t, enabledTools != null && enabledTools.contains(t));
        }
        return writeJson(map);
    }

    private String buildRagJson(Integer topK, Double scoreThreshold, Boolean enabled) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("topK", topK == null ? 5 : topK);
        map.put("scoreThreshold", scoreThreshold == null ? 0.6 : scoreThreshold);
        map.put("enabled", enabled == null || enabled);
        return writeJson(map);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception ex) {
            log.warn("[AgentEntityAppService] JSON serialise failed: {}", ex.getMessage());
            return null;
        }
    }

    private AgentEntityResponse toResponse(AgentEntity e) {
        AgentEntityResponse r = new AgentEntityResponse();
        r.setId(e.getId());
        r.setUserId(e.getUserId());
        r.setWorkspaceId(e.getWorkspaceId());
        r.setName(e.getName());
        r.setDescription(e.getDescription());
        r.setAvatar(e.getAvatar());
        r.setSystemPrompt(e.getSystemPrompt());
        r.setWelcomeMessage(e.getWelcomeMessage());
        r.setToolsConfig(e.getToolsConfig());
        r.setRagConfig(e.getRagConfig());
        r.setMemoryEnabled(e.getMemoryEnabled() != null && e.getMemoryEnabled() == 1);
        r.setIsDefault(e.getIsDefault() != null && e.getIsDefault() == 1);
        r.setStatus(e.getStatus());
        r.setCreateTime(fmt(e.getCreateTime()));
        r.setUpdateTime(fmt(e.getUpdateTime()));
        r.setEnabledTools(parseEnabledTools(e.getToolsConfig()));
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseEnabledTools(String toolsJson) {
        if (toolsJson == null || toolsJson.isBlank()) return new ArrayList<>();
        try {
            Map<String, Object> map = objectMapper.readValue(toolsJson, Map.class);
            List<String> enabled = new ArrayList<>();
            for (String t : ALL_TOOLS) {
                Object v = map.get(t);
                if (Boolean.TRUE.equals(v)) enabled.add(t);
            }
            return enabled;
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String fmt(Date d) {
        return d == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }
}
