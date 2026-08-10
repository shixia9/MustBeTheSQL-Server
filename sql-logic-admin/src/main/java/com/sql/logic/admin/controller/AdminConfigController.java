package com.sql.logic.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.admin.service.AdminUserService;
import com.sql.logic.admin.service.NacosConfigService;
import com.sql.logic.engine.common.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin configuration endpoints — exposes the multi-agent {@code bus-orc}
 * switch (mode/timeout) for in-UI toggling, plus a read-only 6-Agent
 * topology view.
 * <p>
 * Reads/writes go through {@link NacosConfigService} to the
 * {@code sql-logic-service.yml} Nacos dataId. Write operations are
 * restricted to SUPER_ADMIN (manual check, mirroring {@link AdminUserController}).
 * The {@code AdminGuard} interceptor already enforces login + admin status on
 * all {@code /api/v1/admin/**} paths.
 */
@RestController
@RequestMapping("/api/v1/admin/config")
public class AdminConfigController {

    private final NacosConfigService nacosConfigService;
    private final AdminUserService adminUserService;

    public AdminConfigController(NacosConfigService nacosConfigService,
                                 AdminUserService adminUserService) {
        this.nacosConfigService = nacosConfigService;
        this.adminUserService = adminUserService;
    }

    /** Current multi-agent bus-orc configuration + whether the caller may edit. */
    @GetMapping("/agent")
    public Result<Map<String, Object>> getAgentConfig() {
        Map<String, Object> config = nacosConfigService.readBusOrc();
        config.put("needsRestartHint",
                "Switching bus-orc.mode requires restarting sql-logic-service "
                        + "(AgentDispatcher is a startup-created singleton; "
                        + "Nacos hot-refresh will NOT rebuild it).");
        config.put("canEdit", adminUserService.isSuperAdmin(getCurrentUserId()));
        return Result.success(config);
    }

    /**
     * Update bus-orc mode/timeout. SUPER_ADMIN only.
     * Body: {@code {mode: "off"|"bypass"|"switch", dispatcherTimeoutSeconds: long}}
     */
    @PutMapping("/agent")
    public Result<Map<String, Object>> updateAgentConfig(@RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        if (!adminUserService.isSuperAdmin(userId)) {
            return Result.error(403, "Only SUPER_ADMIN can change agent config");
        }
        Object modeRaw = body.get("mode");
        Object timeoutRaw = body.get("dispatcherTimeoutSeconds");
        if (modeRaw == null || timeoutRaw == null) {
            return Result.error(400, "mode and dispatcherTimeoutSeconds are required");
        }
        String mode = String.valueOf(modeRaw);
        long timeout = ((Number) timeoutRaw).longValue();
        try {
            nacosConfigService.writeBusOrc(mode, timeout);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(500, e.getMessage());
        }
        Map<String, Object> result = nacosConfigService.readBusOrc();
        result.put("needsRestartHint",
                "Configuration published to Nacos. Restart sql-logic-service to apply mode changes.");
        result.put("canEdit", true);
        return Result.success(result);
    }

    /** Read-only 6-Agent orchestration topology (mirrors agentic domain profiles). */
    @GetMapping("/agent/topology")
    public Result<List<Map<String, String>>> getAgentTopology() {
        return Result.success(AgentTopology.TOPOLOGY);
    }

    private Long getCurrentUserId() {
        return Long.valueOf((String) StpUtil.getLoginId());
    }

    /** Static 6-Agent topology — name/role/goal for read-only display. */
    private static final class AgentTopology {
        static final List<Map<String, String>> TOPOLOGY = List.of(
                topo("Manager", "编排管理者",
                        "按复杂度路由：简单查询直连 DataScientist，复杂查询经 Planner 分解后调度执行"),
                topo("Planner", "计划分解",
                        "将复杂查询分解为有序执行步骤，标注步骤间依赖关系"),
                topo("DataScientist", "数据科学家",
                        "生成并执行 SQL，支持多候选打分与图表可视化"),
                topo("CodeAssistant", "代码工程师",
                        "生成并执行 Python 数据分析代码（沙箱运行）"),
                topo("ToolAssistant", "工具专家",
                        "调用 MCP 外部工具完成数据采集与外部操作"),
                topo("DashboardAssistant", "报告生成者",
                        "汇总各步骤结果生成最终分析报告")
        );

        private static Map<String, String> topo(String name, String role, String goal) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("role", role);
            m.put("goal", goal);
            return m;
        }
    }
}
