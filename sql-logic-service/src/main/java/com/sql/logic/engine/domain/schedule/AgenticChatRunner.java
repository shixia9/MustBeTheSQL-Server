package com.sql.logic.engine.domain.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.AgenticRunner;
import com.sql.logic.engine.domain.agent.core.AgenticRunner.AgentRunHandle;
import com.sql.logic.engine.infrastructure.po.ScheduledRun;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link ScheduledTaskRunner} — replays a scheduled task's payload through the
 * existing 6-Agent agentic chat stream and collects the final-answer text + artifact
 * count into a {@link RunResult}.
 *
 * <p>Registered for {@code taskType = "chat_replay"} ({@link ScheduleConstants#DEFAULT_TASK_TYPE});
 * {@link RunnerRegistry} falls back to this runner for null/blank task types as well.
 */
@Component
public class AgenticChatRunner implements ScheduledTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AgenticChatRunner.class);

    private final AgenticRunner agenticRunner;
    private final ObjectMapper objectMapper;

    /**
     * Same config the engine reads ({@code schedule.run-timeout-default-s}). Injected so
     * the runner's inner {@code latch.await} fallback timeout stays in sync with the
     * engine's {@code CompletableFuture.orTimeout} — if these two diverge (engine reads
     * the config, runner reads a compile-time constant), lowering the config would leave
     * the runner's reactive stream + ForkJoinPool thread live for the full constant
     * window after the engine already finalized the run as 'timeout'.
     */
    @Value("${schedule.run-timeout-default-s:600}")
    private int defaultTimeout;

    public AgenticChatRunner(AgenticRunner agenticRunner, ObjectMapper objectMapper) {
        this.agenticRunner = agenticRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    public String taskType() {
        return ScheduleConstants.DEFAULT_TASK_TYPE; // "chat_replay"
    }

    @Override
    public RunResult execute(ScheduledTask task, ScheduledRun run) {
        String threadId = null;
        try {
            Long taskId = task != null ? task.getId() : null;
            Long runId = run != null ? run.getId() : null;
            log.info("[AgenticChatRunner] Start: taskId={}, runId={}, taskType={}",
                    taskId, runId, task != null ? task.getTaskType() : null);

            Payload p = parsePayload(task != null ? task.getPayload() : null);
            if (p.userInput == null || p.userInput.isBlank()) {
                log.warn("[AgenticChatRunner] No user_input in payload: taskId={}", taskId);
                return new RunResult("no user_input in payload", null, false);
            }

            Long userId = task != null ? task.getUserId() : null;
            // Use the SAME config the engine reads (schedule.run-timeout-default-s) so the
            // runner's inner latch.await fallback cannot drift from the engine's orTimeout.
            Long timeoutSeconds = (task != null && task.getTimeoutSeconds() != null && task.getTimeoutSeconds() > 0)
                    ? task.getTimeoutSeconds()
                    : (long) defaultTimeout;

            AgentRunHandle handle = agenticRunner.execute(
                    p.connectionId,
                    p.userInput,
                    userId,
                    p.llmConfigId,
                    p.workspaceId,
                    p.tableNames,
                    p.schemaName,
                    true,
                    null,
                    p.conversationHistory,
                    false,
                    null
            );
            threadId = handle.getThreadId();

            List<String> lines = collectLines(handle.getUnifiedSseFlux(), timeoutSeconds, taskId, runId);
            CollectedOut out = reduce(lines);

            String summary = buildSummary(out, ScheduleConstants.SUMMARY_MAX);
            log.info("[AgenticChatRunner] Done: taskId={}, runId={}, threadId={}, lines={}, artifacts={}",
                    taskId, runId, threadId, lines.size(), out.artifacts);
            return new RunResult(summary, threadId);
        } catch (Throwable t) {
            // CRITICAL: runner never throws — capture everything into the summary.
            log.error("[AgenticChatRunner] Failed: threadId={}", threadId, t);
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return new RunResult("runner error: " + truncate(msg, 1900), threadId, false);
        }
    }

    // ---------------------------------------------------------------------
    // SSE collection
    // ---------------------------------------------------------------------

    /**
     * Block until the flux completes (or timeout), accumulating all emitted lines.
     * Uses an explicit {@link CountDownLatch} subscription so partial output is
     * preserved even on timeout/error (the engine enforces an outer
     * {@code CompletableFuture.orTimeout}, but this inner timeout is a second safety
     * net against an infinite stream).
     */
    private List<String> collectLines(Flux<String> flux, long timeoutSeconds, Long taskId, Long runId) {
        List<String> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Disposable disposable = flux
                .doOnNext(collected::add)
                .doOnError(errorRef::set)
                .doFinally(sig -> latch.countDown())
                .subscribe();

        boolean completed;
        try {
            completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        if (!completed) {
            // Timeout — cancel the subscription and keep whatever was collected.
            log.warn("[AgenticChatRunner] Stream timeout after {}s (taskId={}, runId={}); collected {} lines",
                    timeoutSeconds, taskId, runId, collected.size());
            disposable.dispose();
        }
        Throwable err = errorRef.get();
        if (err != null) {
            log.warn("[AgenticChatRunner] Stream errored (taskId={}, runId={}): {}",
                    taskId, runId, err.toString());
        }
        return collected;
    }

    /** Reduce the raw SSE lines into a final-answer summary + artifact count. */
    private CollectedOut reduce(List<String> lines) {
        CollectedOut out = new CollectedOut();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) continue;
            try {
                String json = raw.strip();
                if (json.startsWith("data:")) {
                    json = json.substring(5).strip();
                }
                if (json.isEmpty() || "[DONE]".equals(json)) continue;
                JsonNode node = objectMapper.readTree(json);

                String outputType = textOrNull(node, "outputType");
                String type = textOrNull(node, "type");
                String messageType = textOrNull(node, "messageType");
                JsonNode data = node.get("data");

                // Terminal type-events (defensive — currently emitted by the controller,
                // not AgenticRunner, so we usually won't see them here).
                if (type != null) {
                    if ("ERROR".equals(type)) {
                        String msg = textOrNull(node, "message");
                        if (msg != null && !msg.isBlank()) {
                            out.lastText = msg; // surface the error as the summary
                        }
                    }
                    // COMPLETED / AWAITING_CONFIRMATION carry no answer text — ignore.
                    continue;
                }

                // Only FINISHED node events carry output data worth collecting.
                if (!"FINISHED".equals(outputType) || data == null) {
                    continue;
                }

                // Artifact counting: produced SQL / code / results / reports / charts.
                if (messageType != null
                        && ("TOOL_CALL".equals(messageType)
                        || "TOOL_RESULT".equals(messageType)
                        || "REPORT".equals(messageType))) {
                    out.artifacts++;
                }

                // Final-answer text extraction (priority order).
                String report = textOrNull(data, "report");
                if (report != null && !report.isBlank()) {
                    out.report = report; // highest priority — overwrite
                }
                String analysis = textOrNull(data, "analysis");
                if (analysis != null && !analysis.isBlank()) {
                    if (out.analysis == null) out.analysis = analysis;
                }
                String sqlExec = textOrNull(data, "sqlExecutionResult");
                if (sqlExec != null && !sqlExec.isBlank()) {
                    if (out.sqlExecResult == null) out.sqlExecResult = sqlExec;
                }

                // Track the last non-empty text-bearing field as a fallback.
                String fallback = pickFirstNonBlank(data,
                        "report", "analysis", "sqlExecutionResult", "pythonResult",
                        "pythonCode", "sql", "toolResult", "plan");
                if (fallback != null) {
                    out.lastText = fallback;
                }
            } catch (Exception e) {
                // Malformed line — skip defensively, never throw.
                log.debug("[AgenticChatRunner] Skipping malformed SSE line: {}", e.getMessage());
            }
        }
        return out;
    }

    private String buildSummary(CollectedOut out, int max) {
        String answer = firstNonBlank(out.report, out.analysis, out.sqlExecResult, out.lastText);
        StringBuilder sb = new StringBuilder();
        if (answer != null && !answer.isBlank()) {
            sb.append(answer.strip());
        }
        if (out.artifacts > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("[artifacts: ").append(out.artifacts).append(']');
        }
        if (sb.length() == 0) {
            return null; // no summary — engine will record an empty result_summary
        }
        return truncate(sb.toString(), max);
    }

    // ---------------------------------------------------------------------
    // Payload parsing (tolerant of camelCase / snake_case)
    // ---------------------------------------------------------------------

    private Payload parsePayload(String payloadJson) {
        Payload p = new Payload();
        p.tableNames = List.of();
        if (payloadJson == null || payloadJson.isBlank()) {
            return p;
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            p.userInput = firstNonBlank(
                    textOrNull(root, "userInput"), textOrNull(root, "user_input"));
            p.llmConfigId = firstLong(root, "llmConfigId", "modelConfigId", "model_name", "llm_config_id", "modelConfigId_");
            p.connectionId = firstLong(root, "connectionId", "connection_id");
            p.workspaceId = firstLong(root, "workspaceId", "workspace_id");
            p.schemaName = firstNonBlank(
                    textOrNull(root, "schemaName"), textOrNull(root, "schema_name"), textOrNull(root, "schemaContext"));
            p.conversationHistory = firstNonBlank(
                    textOrNull(root, "conversationHistory"), textOrNull(root, "conversation_history"));
            p.tableNames = readStringList(root, "tableNames", "table_names");
        } catch (Exception e) {
            log.warn("[AgenticChatRunner] Failed to parse payload JSON: {}", e.getMessage());
            // leave defaults — userInput stays null → caller returns "no user_input in payload"
        }
        return p;
    }

    // ---------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null) return null;
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s == null || s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return null;
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    private static String pickFirstNonBlank(JsonNode data, String... fields) {
        if (data == null) return null;
        for (String f : fields) {
            JsonNode n = data.get(f);
            if (n == null || n.isNull()) continue;
            String s = n.asText();
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    private static Long firstLong(JsonNode parent, String... fields) {
        if (parent == null) return null;
        for (String f : fields) {
            JsonNode n = parent.get(f);
            if (n == null || n.isNull()) continue;
            if (n.isNumber()) return n.asLong();
            String s = n.asText();
            if (s != null && !s.isBlank()) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) { /* try next */ }
            }
        }
        return null;
    }

    private static List<String> readStringList(JsonNode parent, String... fields) {
        if (parent == null) return List.of();
        for (String f : fields) {
            JsonNode n = parent.get(f);
            if (n == null || n.isNull()) continue;
            if (n.isArray()) {
                List<String> out = new ArrayList<>(n.size());
                for (JsonNode el : n) {
                    if (el != null && !el.isNull()) {
                        String s = el.asText();
                        if (s != null && !s.isBlank()) out.add(s);
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------------------------------------------------------------
    // Internal holders
    // ---------------------------------------------------------------------

    private static final class Payload {
        String userInput;
        Long llmConfigId;
        Long connectionId;
        Long workspaceId;
        String schemaName;
        String conversationHistory;
        List<String> tableNames = List.of();
    }

    private static final class CollectedOut {
        String report;        // DASHBOARD / REPORT final answer (highest priority)
        String analysis;      // PYTHON_ANALYSIS
        String sqlExecResult; // SQL_EXECUTION / DATA_SCIENTIST
        String lastText;      // last non-empty text-bearing field (fallback)
        int artifacts;        // count of TOOL_CALL / TOOL_RESULT / REPORT FINISHED events
    }
}
