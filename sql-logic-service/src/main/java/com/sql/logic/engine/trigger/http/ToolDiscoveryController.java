package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.agent.tool.ToolDiscoveryService;
import com.sql.logic.engine.domain.agent.tool.ToolItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Unified tool discovery endpoint backing the frontend "/" command palette.
 * <p>
 * Replaces the legacy {@code ToolController}: instead of returning only public
 * builtin tools, this endpoint aggregates builtin + MCP + skill items into a single
 * {@link ToolItem} list scoped to the caller. Authentication is enforced the same
 * way as {@code SkillController} / {@code McpServerController} — {@code StpUtil.getLoginId()}
 * throws {@code NotLoginException} for unauthenticated requests, so no annotation is
 * required. The caller's userId is taken from the Sa-Token session.
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolDiscoveryController {

    private final ToolDiscoveryService discoveryService;

    public ToolDiscoveryController(ToolDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * List every tool-shaped entity visible to the current user: builtin native
     * tools, the user's MCP-connected tools, and the user's accessible skills.
     */
    @GetMapping
    public Result<List<ToolItem>> discover() {
        Long userId = getCurrentUserId();
        return Result.success(discoveryService.discover(userId));
    }

    private Long getCurrentUserId() {
        return Long.valueOf((String) StpUtil.getLoginId());
    }
}
