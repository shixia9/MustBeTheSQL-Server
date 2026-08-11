package com.sql.logic.engine.domain.agent.tool;

import org.springframework.stereotype.Component;

/**
 * Guards tool invocations by verifying that the requested tool is accessible
 * by the calling user's scope before the call is dispatched.
 * <p>
 * A tool is accessible when it is registered either in the user's private
 * scope or in the public (shared) scope. {@code userId=null} (anonymous /
 * public caller) only passes for tools registered in the public scope.
 * <p>
 * On denial a {@link ToolPermissionException} is thrown so the calling action
 * can surface a clean permission error rather than routing to a server that
 * does not own the tool for that user.
 */
@Component
public class ToolInvocationGuard {

    private final ToolRegistry toolRegistry;

    public ToolInvocationGuard(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Verify that {@code toolName} is accessible by {@code userId}.
     *
     * @param userId   owning user id, or {@code null} for an anonymous/public caller
     * @param toolName the tool name to check
     * @throws ToolPermissionException if the tool is not registered in the user's
     *                                 private scope nor the public scope
     */
    public void check(Long userId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new ToolPermissionException("Permission denied: no tool specified");
        }
        if (!toolRegistry.isRegistered(userId, toolName)) {
            throw new ToolPermissionException(
                    "Permission denied: tool not accessible by user");
        }
    }
}
