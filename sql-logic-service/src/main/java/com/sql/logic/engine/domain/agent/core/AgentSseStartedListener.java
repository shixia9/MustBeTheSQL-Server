package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.domain.trace.TraceContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Graph lifecycle listener that emits a STARTED SSE event onto the run's
 * {@link reactor.core.publisher.Sinks.Many} (looked up by threadId in
 * {@link NodeStartedSinkRegistry}) immediately <em>before</em> each real node's
 * action runs.
 *
 * <p>Pseudo/synthetic nodes ({@code __START__}, {@code __END__}) and any node
 * not present in the SSE message-type table are filtered out, mirroring the
 * FINISHED filter. The callback also marks the node as started on the run's
 * {@link TraceContext} (via {@link TraceContext#beginNode}) so that
 * {@code TracingLlmClientWrapper} can attribute the node's LLM token usage to
 * the correct step.
 *
 * <p>Because the listener is registered on the {@code CompileConfig} shared by
 * both {@code execute()} and {@code resume()}, a single instance covers both
 * paths. The {@code interruptBefore(HITL)} short-circuit in {@code MainGraphExecutor}
 * means {@code before()} is NOT invoked for HITL while the run is paused, so no
 * spurious STARTED is emitted mid-pause.
 */
@Component
public class AgentSseStartedListener implements GraphLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(AgentSseStartedListener.class);

    private static final Set<String> KNOWN_NODES = Set.of(
            "MEMORY_RECALL",
            "EVIDENCE_RECALL", "SCHEMA_LINKING", "FEASIBILITY_ASSESSMENT", "PLANNER",
            "HITL_GATE", "HITL", "PLAN_DISPATCH",
            "SQL_GENERATION", "SQL_EXECUTION", "SQL_FIXER",
            "PYTHON_GENERATION", "PYTHON_EXECUTION", "PYTHON_ANALYSIS",
            "REPORT",
            // 6-Agent Multi-Agent system nodes
            "MANAGER", "DATA_SCIENTIST", "CODE_ASSISTANT",
            "DASHBOARD", "TOOL_ASSISTANT"
    );

    private final NodeStartedSinkRegistry sinkRegistry;
    private final TraceContextRegistry traceContextRegistry;
    private final AgentSseCodec codec;

    public AgentSseStartedListener(NodeStartedSinkRegistry sinkRegistry,
                                   TraceContextRegistry traceContextRegistry,
                                   AgentSseCodec codec) {
        this.sinkRegistry = sinkRegistry;
        this.traceContextRegistry = traceContextRegistry;
        this.codec = codec;
    }

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        if (nodeId == null) return;
        String name = nodeId.startsWith("__") ? nodeId : nodeId.toUpperCase();
        if (!KNOWN_NODES.contains(name)) return;

        try {
            Optional<String> threadIdOpt = config == null ? Optional.empty() : config.threadId();
            if (threadIdOpt.isEmpty()) return;
            String threadId = threadIdOpt.get();

            // Attribute token usage to this node before its LLM call runs.
            Optional<TraceContext> tcOpt = traceContextRegistry.get(threadId);
            tcOpt.ifPresent(tc -> tc.beginNode(name));

            var sink = sinkRegistry.get(threadId);
            if (sink == null) return;

            // Build extra context for looped nodes so the frontend can distinguish visits
            Map<String, Object> extra = Map.of();
            if (SqlAgentSpec.Node.PLAN_DISPATCH.equals(name) && state != null) {
                Object step = state.get(SqlAgentSpec.StateKey.CURRENT_STEP);
                if (step != null) {
                    extra = Map.of("currentStep", step);
                }
            }

            String json = codec.startedJson(name, extra);
            if (json != null && !json.isEmpty()) {
                sink.tryEmitNext(json);
            }
        } catch (Exception e) {
            // Never let a listener failure affect graph execution.
            log.debug("[AgentSseStartedListener] before({}) failed: {}", nodeId, e.getMessage());
        }
    }
}