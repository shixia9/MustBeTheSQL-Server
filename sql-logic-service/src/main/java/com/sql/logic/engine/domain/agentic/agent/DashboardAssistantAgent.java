package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;
import com.sql.logic.engine.domain.agentic.vis.ChartType;

import java.util.List;
import java.util.Map;

/**
 * Dashboard / Report Agent — collects results from all completed plan steps
 * and generates a comprehensive final report with structured chart data.
 * <p>
 * Phase 6 (DB-GPT level): Extracts SQLs from conversation history, re-executes
 * them to obtain fresh data, assembles structured {@code vis-dashboard} JSON
 * for frontend grid rendering. Falls back to Markdown report if no SQLs found.
 */
public class DashboardAssistantAgent extends ConversableAgent {

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("DashboardAssistant")
            .role("报告生成者")
            .goal("汇总所有分析步骤的结果，提取SQL并重新执行以获取图表数据，生成结构化仪表盘报告")
            .constraints(List.of(
                    "仅使用已提供的步骤结果，不要自行生成分析数据",
                    "从历史消息中提取所有分析SQL，重新执行以获取最新数据",
                    "为每个SQL选择合适的图表展示类型",
                    "报告应结构清晰，包含分析概述、关键发现和建议"
            ))
            .description("专业的数据分析报告与仪表盘生成专家")
            .systemPromptTemplate("""
                    你是 {name}，{description}。
                    角色：{role}
                    目标：{goal}

                    约束条件：
                    {constraints}

                    """ + ChartType.buildChartTypePrompt() + """

                    从历史消息中提取每条分析SQL，为每条SQL选择合适的display_type，
                    然后以JSON数组格式输出: [{"title": "...", "display_type": "...", "sql": "...", "thought": "..."}]

                    不要生成新的分析SQL，仅收集和整理已存在的SQL。
                    """)
            .build();

    public DashboardAssistantAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    @Override
    protected String buildSystemPrompt(String observation, String memoryContext,
                                        String resourceContext, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderProfilePrompt());
        sb.append("\n");

        // Inject all step results from context
        @SuppressWarnings("unchecked")
        List<Map<String, String>> stepResults =
                (List<Map<String, String>>) context.get("stepResults");
        if (stepResults != null && !stepResults.isEmpty()) {
            sb.append("### 分析步骤及结果\n");
            int i = 1;
            for (Map<String, String> step : stepResults) {
                sb.append("步骤").append(i++).append(": ")
                        .append(step.getOrDefault("content", ""))
                        .append("\n  结果: ").append(step.getOrDefault("result", ""))
                        .append("\n");
            }
            sb.append("\n");
        }

        if (resourceContext != null && !resourceContext.isBlank()) {
            sb.append(resourceContext);
        }
        return sb.toString();
    }

    @Override
    protected String buildUserPrompt(String observation, String memoryContext,
                                      String resourceContext, Map<String, Object> context) {
        return observation;
    }
}
