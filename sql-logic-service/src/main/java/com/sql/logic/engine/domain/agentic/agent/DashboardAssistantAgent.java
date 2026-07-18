package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;

import java.util.List;
import java.util.Map;

/**
 * Dashboard / Report Agent — collects results from all completed plan steps
 * and generates a comprehensive final report.
 */
public class DashboardAssistantAgent extends ConversableAgent {

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("DashboardAssistant")
            .role("报告生成者")
            .goal("汇总所有分析步骤的结果，生成结构化的数据分析报告")
            .constraints(List.of(
                    "仅使用已提供的步骤结果，不要自行生成分析数据",
                    "报告应结构清晰，包含分析概述、关键发现和建议",
                    "使用 Markdown 格式输出"
            ))
            .description("专业的数据分析报告撰写专家")
            .systemPromptTemplate("""
                    你是 {name}，{description}。
                    角色：{role}
                    目标：{goal}

                    约束条件：
                    {constraints}

                    请基于提供的步骤分析结果，生成一份完整的分析报告。
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
