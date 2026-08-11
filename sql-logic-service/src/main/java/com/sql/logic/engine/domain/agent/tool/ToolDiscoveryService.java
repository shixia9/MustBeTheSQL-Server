package com.sql.logic.engine.domain.agent.tool;

import com.sql.logic.engine.domain.skill.SkillCatalogService;
import com.sql.logic.engine.infrastructure.po.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates every tool-shaped entity visible to a user into a single
 * flat {@link ToolItem} list for the frontend "/" command palette.
 * <p>
 * Two sources are merged:
 * <ul>
 *   <li>{@link ToolRegistry} — built-in native tools plus the user's
 *       own MCP-discovered tools. Both are real tool calls, so their
 *       {@code invocationMode} is {@code call_tool}.</li>
 *   <li>{@link SkillCatalogService} — DB-backed skills.
 *       Skills are invoked by injecting a rendered prompt,
 *       so their {@code invocationMode} is {@code inject_prompt}.</li>
 * </ul>
 */
@Service
public class ToolDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ToolDiscoveryService.class);

    private final ToolRegistry toolRegistry;
    private final SkillCatalogService skillCatalogService;

    public ToolDiscoveryService(ToolRegistry toolRegistry, SkillCatalogService skillCatalogService) {
        this.toolRegistry = toolRegistry;
        this.skillCatalogService = skillCatalogService;
    }

    /**
     * Build the unified tool list visible to {@code userId}: builtin + mcp tools
     * from the registry, followed by skills from the catalog.
     */
    public List<ToolItem> discover(Long userId) {
        List<ToolItem> items = new ArrayList<>();

        // --- Registry: builtin (public) + the user's MCP tools ---
        for (ToolDefinition def : toolRegistry.listTools(userId)) {
            items.add(toToolItem(def));
        }

        // --- Catalog: the user's private skills + public skills ---
        for (Skill skill : skillCatalogService.listByUser(userId)) {
            items.add(toToolItem(skill));
        }

        log.debug("[ToolDiscovery] userId={} discovered {} items ({} tools, {} skills)",
                userId, items.size(),
                items.size() - (int) items.stream().filter(i -> "skill".equals(i.kind())).count(),
                items.stream().filter(i -> "skill".equals(i.kind())).count());
        return items;
    }

    private ToolItem toToolItem(ToolDefinition def) {
        String kind;
        String invocationMode;
        switch (def.source()) {
            case BUILTIN -> {
                kind = "builtin";
                invocationMode = "call_tool";
            }
            case MCP -> {
                kind = "mcp";
                invocationMode = "call_tool";
            }
            case SKILL -> {
                // Defensive: a skill-backed tool registered directly in the registry
                // (rather than the skill table) is still prompt-injected.
                kind = "skill";
                invocationMode = "inject_prompt";
            }
            default -> {
                kind = "builtin";
                invocationMode = "call_tool";
            }
        }
        return new ToolItem(
                kind,
                def.name(),
                def.displayName(),
                def.description(),
                def.parametersSchema(),
                invocationMode,
                def.source().name()
        );
    }

    private ToolItem toToolItem(Skill skill) {
        return new ToolItem(
                "skill",
                skill.getName(),
                skill.getName(),
                skill.getDescription(),
                null,
                "inject_prompt",
                ToolSource.SKILL.name()
        );
    }
}
