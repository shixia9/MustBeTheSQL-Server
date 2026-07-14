package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.AgentEntityAppService;
import com.sql.logic.engine.application.service.AgentVersionAppService;
import com.sql.logic.engine.common.dto.AgentEntityRequest;
import com.sql.logic.engine.common.dto.AgentEntityResponse;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.agent.config.AgentRuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Phase B (B4) Agent Studio REST API.
 * <p>
 * CRUD over a user's Agent configurations plus default-Agent election. The selected
 * default drives the Agent runtime via {@link AgentRuntimeConfigService}.
 */
@RestController
@RequestMapping("/api/v1/agent-entity")
public class AgentEntityController {

    private static final Logger log = LoggerFactory.getLogger(AgentEntityController.class);

    private final AgentEntityAppService agentEntityAppService;
    private final AgentVersionAppService agentVersionAppService;
    private final AgentRuntimeConfigService agentRuntimeConfigService;

    public AgentEntityController(AgentEntityAppService agentEntityAppService,
                                AgentVersionAppService agentVersionAppService,
                                AgentRuntimeConfigService agentRuntimeConfigService) {
        this.agentEntityAppService = agentEntityAppService;
        this.agentVersionAppService = agentVersionAppService;
        this.agentRuntimeConfigService = agentRuntimeConfigService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @GetMapping("/list")
    public Result<List<AgentEntityResponse>> list() {
        Long userId = getCurrentUserId();
        return Result.success(agentEntityAppService.listAccessibleAgents(userId));
    }

    @GetMapping("/{id}")
    public Result<AgentEntityResponse> get(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        AgentEntityResponse r = agentEntityAppService.getById(id, userId);
        return r == null ? Result.error(404, "Agent not found") : Result.success(r);
    }

    @PostMapping
    public Result<AgentEntityResponse> create(@RequestBody AgentEntityRequest req) {
        Long userId = getCurrentUserId();
        if (req.getName() == null || req.getName().isBlank()) {
            return Result.error(400, "Agent name is required");
        }
        AgentEntityResponse r = agentEntityAppService.create(userId, null, req);
        agentRuntimeConfigService.invalidate(userId);
        return Result.success(r);
    }

    @PutMapping("/{id}")
    public Result<AgentEntityResponse> update(@PathVariable Long id, @RequestBody AgentEntityRequest req) {
        Long userId = getCurrentUserId();
        AgentEntityResponse r = agentEntityAppService.update(id, userId, req);
        if (r == null) return Result.error(404, "Agent not found");
        agentRuntimeConfigService.invalidate(userId);
        return Result.success(r);
    }

    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (!agentEntityAppService.setDefault(id, userId)) {
            return Result.error(404, "Agent not found");
        }
        agentRuntimeConfigService.invalidate(userId);
        log.info("[AgentEntityController] Set default agent id={} for userId={}", id, userId);
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (!agentEntityAppService.delete(id, userId)) {
            return Result.error(404, "Agent not found");
        }
        agentRuntimeConfigService.invalidate(userId);
        return Result.success(null);
    }

    // ---- Phase D3: Agent version management ----

    @PostMapping("/{id}/publish")
    public Result<Map<String, Object>> publishVersion(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        try {
            var v = agentVersionAppService.publish(id, userId);
            return Result.success(Map.of(
                    "id", v.getId(),
                    "versionNumber", v.getVersionNumber(),
                    "publishTime", v.getPublishTime().toString()
            ));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/{id}/versions")
    public Result<List<Map<String, Object>>> listVersions(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        try {
            return Result.success(agentVersionAppService.listVersions(id, userId));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/{id}/versions/{versionId}")
    public Result<String> getVersionSnapshot(@PathVariable Long id, @PathVariable Long versionId) {
        Long userId = getCurrentUserId();
        String snapshot = agentVersionAppService.getSnapshot(versionId, id, userId);
        return snapshot == null ? Result.error(404, "Version not found") : Result.success(snapshot);
    }

    @PostMapping("/{id}/versions/{versionId}/revert")
    public Result<Void> revertToVersion(@PathVariable Long id, @PathVariable Long versionId) {
        Long userId = getCurrentUserId();
        try {
            agentVersionAppService.revert(versionId, id, userId);
            agentRuntimeConfigService.invalidate(userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @DeleteMapping("/{id}/versions/{versionId}")
    public Result<Void> deleteVersion(@PathVariable Long id, @PathVariable Long versionId) {
        Long userId = getCurrentUserId();
        try {
            agentVersionAppService.deleteVersion(versionId, id, userId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
