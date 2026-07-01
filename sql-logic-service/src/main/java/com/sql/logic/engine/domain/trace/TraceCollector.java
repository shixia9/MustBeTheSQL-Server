package com.sql.logic.engine.domain.trace;

import com.sql.logic.engine.infrastructure.po.AgentExecution;
import com.sql.logic.engine.infrastructure.po.AgentExecutionStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
 * Collects trace data during graph execution. Used by SqlAgentController
 * to transform TraceContext into persistent AgentExecution/Step records.
 *
 * DESIGN NOTE: No changes to individual node logic. Trace injection happens
 * in SqlAgentController.nodeOutputToJson() — the single serialization point
 * for all node outputs.
 */
@Component
public class TraceCollector {
    private static final Logger log = LoggerFactory.getLogger(TraceCollector.class);

    /**
     * Build the execution summary from a TraceContext.
     */
    public AgentExecution buildExecution(TraceContext ctx, String threadId, Long connectionId, String schemaName) {
        AgentExecution exec = new AgentExecution();
        exec.setThreadId(threadId);
        exec.setUserId(ctx.getUserId());
        exec.setWorkspaceId(ctx.getWorkspaceId());
        exec.setConnectionId(connectionId);
        exec.setSchemaName(schemaName);
        exec.setTotalTokens(ctx.getTotalInputTokens() + ctx.getTotalOutputTokens());
        exec.setModelCalls(ctx.getModelCalls());
        exec.setTotalDurationMs(System.currentTimeMillis() - ctx.getStartTime());
        exec.setStatus("COMPLETED");
        exec.setCreateTime(LocalDateTime.now());
        return exec;
    }

    /**
     * Build per-node step records from a TraceContext.
     */
    public List<AgentExecutionStep> buildSteps(TraceContext ctx, Long executionId) {
        List<AgentExecutionStep> steps = new ArrayList<>();
        ConcurrentMap<String, TraceContext.StepTrace> stepMap = ctx.getSteps();
        LocalDateTime now = LocalDateTime.now();

        for (TraceContext.StepTrace st : stepMap.values()) {
            AgentExecutionStep step = new AgentExecutionStep();
            step.setExecutionId(executionId);
            step.setNodeName(st.nodeName);
            step.setSequenceNo(st.sequence);
            step.setStatus(st.status);
            step.setDurationMs(st.latencyMs);
            step.setInputTokens(st.inputTokens);
            step.setOutputTokens(st.outputTokens);
            step.setLatencyMs(st.latencyMs);
            step.setNodeType(st.nodeType);
            step.setOutputData(null);  // populated separately from step buffer
            step.setCreateTime(now);
            steps.add(step);
        }
        return steps;
    }
}
