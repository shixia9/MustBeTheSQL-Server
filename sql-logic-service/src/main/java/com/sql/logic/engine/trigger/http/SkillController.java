package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.skill.SkillCatalogService;
import com.sql.logic.engine.domain.skill.SkillExecutor;
import com.sql.logic.engine.infrastructure.po.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the lightweight Skill system.
 * <p>
 * CRUD + execute endpoints for DB-backed skills (packaged prompt templates
 * optionally bound to tools). Replaces the legacy in-memory SkillController.
 * userId is taken from the Sa-Token session (matching {@code McpServerController});
 * ownership checks are delegated to {@link SkillCatalogService}.
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillCatalogService catalogService;
    private final SkillExecutor executor;

    public SkillController(SkillCatalogService catalogService, SkillExecutor executor) {
        this.catalogService = catalogService;
        this.executor = executor;
    }

    @PostMapping
    public Result<Skill> create(@RequestBody Skill skill) {
        Long userId = getCurrentUserId();
        try {
            return Result.success(catalogService.create(userId, skill));
        } catch (Exception e) {
            log.warn("[SkillController] create failed: {}", e.getMessage());
            return Result.error(500, "Failed to create skill: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Skill> update(@PathVariable Long id, @RequestBody Skill skill) {
        Long userId = getCurrentUserId();
        try {
            return Result.success(catalogService.update(userId, id, skill));
        } catch (IllegalStateException e) {
            // Not found or not owned by the caller.
            return Result.error(403, e.getMessage());
        } catch (Exception e) {
            log.warn("[SkillController] update failed: {}", e.getMessage());
            return Result.error(500, "Failed to update skill: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        try {
            catalogService.delete(userId, id);
            return Result.success(null);
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        } catch (Exception e) {
            log.warn("[SkillController] delete failed: {}", e.getMessage());
            return Result.error(500, "Failed to delete skill: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<List<Skill>> list() {
        Long userId = getCurrentUserId();
        return Result.success(catalogService.listByUser(userId));
    }

    @PostMapping("/{id}/execute")
    public Result<String> execute(@PathVariable Long id, @RequestBody(required = false) Map<String, String> variables) {
        Long userId = getCurrentUserId();
        try {
            return Result.success(executor.execute(id, userId, variables));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        } catch (Exception e) {
            log.warn("[SkillController] execute failed: {}", e.getMessage());
            return Result.error(500, "Failed to execute skill: " + e.getMessage());
        }
    }

    private Long getCurrentUserId() {
        return Long.valueOf((String) StpUtil.getLoginId());
    }
}
