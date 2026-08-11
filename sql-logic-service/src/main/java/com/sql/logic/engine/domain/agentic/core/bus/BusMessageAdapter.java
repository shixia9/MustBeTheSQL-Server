package com.sql.logic.engine.domain.agentic.core.bus;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agentic.core.ActionOutput;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between the rich {@link AgentMessage} world
 * and the lean {@link BusMessage} protocol.
 *
 * <p>Because {@link BusMessage.TaskDispatch} carries only a free-form
 * {@code task} string, this adapter packs a full dispatch envelope (goal content
 * + context + rely messages) into that string as JSON, and likewise packs a
 * reply envelope (content + success + action report) into {@link
 * BusMessage.ToolResult#result()}. This keeps the REQ-01 sealed protocol stable
 * (no new subtypes) while letting {@link BusOrchestrationMode#SWITCH} mode
 * transport full-fidelity business messages.
 *
 * <p>Context values that are not JSON-safe (e.g. ad-hoc objects a future feature
 * might stash in the context map) are silently coerced to their {@code toString}
 * form so that envelope serialisation never throws — a non-serialisable context
 * entry degrades to a string rather than breaking the dispatch.
 */
public final class BusMessageAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private BusMessageAdapter() {}

    // ------------------------------------------------------------------
    //  Envelope records (Jackson-friendly; AgentMessage itself is immutable
    //  without a JsonCreator, so we round-trip through these).
    // ------------------------------------------------------------------

    /** Serialisable form of a dispatch goal + its dependency rely messages. */
    public record GoalEnvelope(
            String content,
            String currentGoal,
            String senderName,
            String senderRole,
            int rounds,
            String messageType,
            Map<String, Object> context,
            List<GoalEnvelope> relyMessages
    ) {
        public GoalEnvelope {
            context = context != null ? Map.copyOf(context) : Map.of();
            relyMessages = relyMessages != null ? List.copyOf(relyMessages) : List.of();
        }
    }

    /** Serialisable form of a worker's reply action output. */
    public record ReplyActionOutput(
            boolean success,
            String content,
            Map<String, Object> data,
            boolean hasRetry
    ) {
        public ReplyActionOutput {
            data = data != null ? Map.copyOf(data) : Map.of();
        }
    }

    /** Serialisable form of a worker's reply. */
    public record ReplyEnvelope(
            String content,
            boolean success,
            int rounds,
            String messageType,
            Map<String, Object> context,
            ReplyActionOutput actionReport,
            String error
    ) {}

    // ------------------------------------------------------------------
    //  Encoding (AgentMessage → envelope JSON)
    // ------------------------------------------------------------------

    /** Encode a goal + rely messages into a JSON envelope string for {@code TaskDispatch.task}. */
    public static String encodeGoalEnvelope(AgentMessage goal, List<AgentMessage> relyMessages) {
        List<GoalEnvelope> rely = new ArrayList<>();
        if (relyMessages != null) {
            for (AgentMessage m : relyMessages) {
                rely.add(toGoalEnvelope(m, null));
            }
        }
        return writeJson(toGoalEnvelope(goal, rely));
    }

    private static GoalEnvelope toGoalEnvelope(AgentMessage m, List<GoalEnvelope> rely) {
        return new GoalEnvelope(
                m.content(),
                m.currentGoal(),
                m.senderName(),
                m.senderRole(),
                m.rounds(),
                m.messageType() != null ? m.messageType().name() : null,
                sanitizeContext(m.context()),
                rely
        );
    }

    /** Encode a reply into a JSON envelope string for {@code ToolResult.result}. */
    public static String encodeReplyEnvelope(AgentMessage reply) {
        ReplyActionOutput ao = null;
        if (reply.actionReport() != null) {
            ActionOutput a = reply.actionReport();
            ao = new ReplyActionOutput(a.success(), a.content(), sanitizeContext(a.data()), a.hasRetry());
        }
        ReplyEnvelope env = new ReplyEnvelope(
                reply.content(),
                reply.success(),
                reply.rounds(),
                reply.messageType() != null ? reply.messageType().name() : null,
                sanitizeContext(reply.context()),
                ao,
                null
        );
        return writeJson(env);
    }

    /** Encode an execution error into a reply envelope (for SWITCH failure paths). */
    public static String encodeErrorEnvelope(Throwable error) {
        String msg = error != null && error.getMessage() != null
                ? error.getMessage() : (error != null ? error.getClass().getSimpleName() : "unknown");
        return writeJson(new ReplyEnvelope(msg, false, 0, null, Map.of(), null, msg));
    }

    // ------------------------------------------------------------------
    //  Decoding (envelope JSON → AgentMessage)
    // ------------------------------------------------------------------

    /** Decode a {@code TaskDispatch.task} envelope back into the goal message + rely messages. */
    public static DecodedGoal decodeGoalEnvelope(String json) {
        GoalEnvelope env = readJson(json, GoalEnvelope.class);
        return new DecodedGoal(toAgentMessage(env), toRelyMessages(env));
    }

    /** Decode a {@code ToolResult.result} envelope back into a reply message. */
    public static AgentMessage decodeReplyEnvelope(String json) {
        ReplyEnvelope env = readJson(json, ReplyEnvelope.class);
        AgentMessage.Builder b = AgentMessage.builder()
                .content(env.content())
                .success(env.success())
                .rounds(env.rounds());
        if (env.messageType() != null) {
            try { b.messageType(AgentMessage.MessageType.valueOf(env.messageType())); }
            catch (IllegalArgumentException ignored) {}
        }
        if (env.context() != null) b.context(env.context());
        if (env.actionReport() != null) {
            ReplyActionOutput a = env.actionReport();
            b.actionReport(new ActionOutput(a.success(), a.content(), a.data(), List.of(), a.hasRetry()));
        }
        return b.build();
    }

    private static AgentMessage toAgentMessage(GoalEnvelope env) {
        AgentMessage.Builder b = AgentMessage.builder()
                .content(env.content())
                .currentGoal(env.currentGoal())
                .senderName(env.senderName())
                .senderRole(env.senderRole())
                .rounds(env.rounds());
        if (env.messageType() != null) {
            try { b.messageType(AgentMessage.MessageType.valueOf(env.messageType())); }
            catch (IllegalArgumentException ignored) {}
        }
        if (env.context() != null) b.context(env.context());
        return b.build();
    }

    private static List<AgentMessage> toRelyMessages(GoalEnvelope env) {
        List<AgentMessage> rely = new ArrayList<>();
        if (env.relyMessages() != null) {
            for (GoalEnvelope r : env.relyMessages()) {
                rely.add(toAgentMessage(r));
            }
        }
        return rely;
    }

    /** Result of decoding a dispatch envelope: the goal plus its dependency messages. */
    public record DecodedGoal(AgentMessage goal, List<AgentMessage> relyMessages) {
        public DecodedGoal {
            relyMessages = relyMessages != null ? List.copyOf(relyMessages) : List.of();
        }
    }

    // ------------------------------------------------------------------
    //  BusMessage factories
    // ------------------------------------------------------------------

    /** Build a point-to-point {@link BusMessage.TaskDispatch} from sender → target. */
    public static BusMessage.TaskDispatch toTaskDispatch(
            String senderName, String targetName, String correlationId, String taskEnvelope) {
        return new BusMessage.TaskDispatch(
                BusMessage.BusHeader.builder()
                        .senderName(senderName)
                        .receiverName(targetName)
                        .correlationId(correlationId)
                        .build(),
                targetName,
                taskEnvelope);
    }

    /** Build a point-to-point {@link BusMessage.ToolResult} reply from worker → manager. */
    public static BusMessage.ToolResult toToolResult(
            String senderName, String receiverName, String correlationId,
            boolean success, String resultEnvelope) {
        return new BusMessage.ToolResult(
                BusMessage.BusHeader.builder()
                        .senderName(senderName)
                        .receiverName(receiverName)
                        .correlationId(correlationId)
                        .build(),
                senderName,
                success,
                resultEnvelope);
    }

    // ------------------------------------------------------------------
    //  JSON helpers + context sanitisation
    // ------------------------------------------------------------------

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // Should not happen for our envelope records; degrade to a minimal
            // string so the bus never silently drops a dispatch.
            return "{\"error\":\"serialization_failed:" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static <T> T readJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode bus envelope: " + e.getMessage(), e);
        }
    }

    /**
     * Coerce a context map to JSON-safe values. Primitives/String/Number/Boolean
     * and Map/List thereof pass through; anything else becomes its {@code toString}.
     * This guarantees {@link #encodeGoalEnvelope} / {@link #encodeReplyEnvelope}
     * never throw on an exotic context entry.
     */
    private static Map<String, Object> sanitizeContext(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : source.entrySet()) {
            out.put(e.getKey(), jsonSafe(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object jsonSafe(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), jsonSafe(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(jsonSafe(o));
            return out;
        }
        // Enums (e.g. MessageType) — Jackson would serialise by name anyway.
        if (value instanceof Enum<?> e) return e.name();
        // Anything else (TraceContext, Future, ...) — coerce to string to stay JSON-safe.
        return value.toString();
    }
}
