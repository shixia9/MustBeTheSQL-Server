package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed registry of pending HITL sessions, keyed by threadId.
 *
 * <p>The graph checkpoint is stored by the agentic RedisSaver. This registry
 * stores the authorization/context envelope needed to locate and resume that
 * checkpoint. A Redis lock makes claim-and-delete atomic across instances.</p>
 */
@Component
public class HitlSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(HitlSessionRegistry.class);
    public static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    public static final int MAX_SESSION_BYTES = 64 * 1024;
    private static final String KEY_PREFIX = "sql:agentic:hitl:session:";
    private static final String LOCK_PREFIX = "sql:agentic:hitl:lock:";
    private static final String CONSUMED_PREFIX = "sql:agentic:hitl:consumed:";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final HitlMetrics metrics;

    public HitlSessionRegistry(RedissonClient redissonClient, ObjectMapper objectMapper,
                               HitlMetrics metrics) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public void register(AgentRunContext context) {
        if (context == null || context.getThreadId() == null) return;
        try {
            SessionRecord record = SessionRecord.from(context);
            String json = objectMapper.writeValueAsString(record);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SESSION_BYTES) {
                throw new IllegalStateException("HITL session envelope exceeds the size limit");
            }
            bucket(context.getThreadId()).set(json,
                    Duration.ofMillis(SESSION_TTL_MS));
            consumedBucket(context.getThreadId()).delete();
            metrics.sessionRegistered();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist HITL session", e);
        }
    }

    public Optional<AgentRunContext> get(String threadId) {
        if (threadId == null || threadId.isBlank()) return Optional.empty();
        return read(threadId);
    }

    public void remove(String threadId) {
        if (threadId != null && !threadId.isBlank()) {
            bucket(threadId).delete();
            writeAudit(threadId, SessionStatus.COMPLETED);
        }
    }

    /** Atomically consumes a pending session; only the winner may resume the graph. */
    public Optional<AgentRunContext> claim(String threadId, Long userId) {
        ClaimResult result = claimDetailed(threadId, userId);
        return result.status() == ClaimStatus.CLAIMED
                ? Optional.of(result.context()) : Optional.empty();
    }

    /** Detailed claim result used by the agentic HTTP protocol. */
    public ClaimResult claimDetailed(String threadId, Long userId) {
        ClaimResult result = doClaimDetailed(threadId, userId);
        metrics.claim(result.status().name());
        return result;
    }

    private ClaimResult doClaimDetailed(String threadId, Long userId) {
        if (threadId == null || threadId.isBlank()) {
            return ClaimResult.failure(ClaimStatus.EXPIRED, "The HITL session is missing or expired");
        }
        RLock lock = redissonClient.getLock(LOCK_PREFIX + threadId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) return ClaimResult.failure(ClaimStatus.RESUME_IN_PROGRESS,
                    "Another resume request is already claiming this session");
            String json = bucket(threadId).get();
            if (json == null || json.isBlank()) {
                SessionAudit audit = readAudit(threadId);
                if (audit != null && SessionStatus.CLAIMED.name().equals(audit.status)) {
                    return ClaimResult.failure(ClaimStatus.RESUME_IN_PROGRESS,
                            "The HITL session is already being resumed");
                }
                return audit != null
                        ? ClaimResult.failure(ClaimStatus.ALREADY_CONSUMED,
                        "The HITL decision has already been consumed")
                        : ClaimResult.failure(ClaimStatus.EXPIRED,
                        "The HITL session is missing or expired");
            }
            AgentRunContext context;
            try {
                context = objectMapper.readValue(json, SessionRecord.class).toContext();
            } catch (Exception e) {
                log.warn("[HitlSessionRegistry] Failed to decode session {}: {}", threadId, e.getMessage());
                return ClaimResult.failure(ClaimStatus.STORAGE_ERROR,
                        "The HITL session cannot be decoded");
            }
            if (userId != null && !userId.equals(context.getUserId())) {
                log.warn("[HitlSessionRegistry] HITL session forbidden threadId={} userId={} ownerId={}",
                        threadId, userId, context.getUserId());
                return ClaimResult.failure(ClaimStatus.FORBIDDEN,
                        "The HITL session belongs to another user");
            }
            bucket(threadId).delete();
            writeAudit(threadId, SessionStatus.CLAIMED);
            return ClaimResult.claimed(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ClaimResult.failure(ClaimStatus.RESUME_IN_PROGRESS,
                    "Resume claim was interrupted");
        } catch (Exception e) {
            log.warn("[HitlSessionRegistry] Failed to claim session {}: {}", threadId, e.getMessage());
            return ClaimResult.failure(ClaimStatus.STORAGE_ERROR,
                    "HITL session storage is unavailable");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /** Restore a claimed session after a checkpoint update failure or stale version. */
    public void restore(AgentRunContext context) {
        register(context);
    }

    /** Mark a claimed session after the resumed graph reaches a terminal state. */
    public void complete(String threadId, SessionStatus status) {
        if (threadId == null || threadId.isBlank() || status == null) return;
        SessionAudit audit = readAudit(threadId);
        if (audit != null && SessionStatus.CLAIMED.name().equals(audit.status)) {
            writeAudit(threadId, status);
        }
    }

    public boolean belongsTo(String threadId, Long userId) {
        return get(threadId).map(ctx -> userId != null && userId.equals(ctx.getUserId())).orElse(false);
    }

    private Optional<AgentRunContext> read(String threadId) {
        try {
            String json = bucket(threadId).get();
            if (json == null || json.isBlank()) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, SessionRecord.class).toContext());
        } catch (Exception e) {
            log.warn("[HitlSessionRegistry] Failed to read session {}: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    private RBucket<String> bucket(String threadId) {
        return redissonClient.getBucket(KEY_PREFIX + threadId);
    }

    private RBucket<String> consumedBucket(String threadId) {
        return redissonClient.getBucket(CONSUMED_PREFIX + threadId);
    }

    private void writeAudit(String threadId, SessionStatus status) {
        try {
            consumedBucket(threadId).set(objectMapper.writeValueAsString(
                            new SessionAudit(status.name(), System.currentTimeMillis())),
                    Duration.ofMillis(SESSION_TTL_MS));
        } catch (Exception e) {
            log.warn("[HitlSessionRegistry] Failed to write session audit {}: {}",
                    threadId, e.getMessage());
        }
    }

    private SessionAudit readAudit(String threadId) {
        try {
            String json = consumedBucket(threadId).get();
            if (json == null || json.isBlank()) return null;
            if ("1".equals(json)) return new SessionAudit(SessionStatus.COMPLETED.name(), 0L);
            return objectMapper.readValue(json, SessionAudit.class);
        } catch (Exception e) {
            log.warn("[HitlSessionRegistry] Failed to read session audit {}: {}",
                    threadId, e.getMessage());
            return null;
        }
    }

    public enum SessionStatus {
        WAITING, CLAIMED, COMPLETED, REJECTED
    }

    public record SessionAudit(String status, long changedAt) {}

    public enum ClaimStatus {
        CLAIMED, FORBIDDEN, EXPIRED, ALREADY_CONSUMED, RESUME_IN_PROGRESS, STORAGE_ERROR
    }

    public record ClaimResult(ClaimStatus status, AgentRunContext context, String message) {
        static ClaimResult claimed(AgentRunContext context) {
            return new ClaimResult(ClaimStatus.CLAIMED, context, "");
        }

        static ClaimResult failure(ClaimStatus status, String message) {
            return new ClaimResult(status, null, message);
        }
    }

    /** Serializable envelope; sinks and trace objects intentionally stay process-local. */
    public static class SessionRecord {
        public String threadId;
        public Long userId;
        public Long connectionId;
        public Long llmConfigId;
        public Long workspaceId;
        public String schemaName;
        public List<String> tableNames;
        public boolean autoConfirm;
        public Long conversationId;
        public String checkpointId;

        public SessionRecord() {}

        static SessionRecord from(AgentRunContext context) {
            SessionRecord record = new SessionRecord();
            record.threadId = context.getThreadId();
            record.userId = context.getUserId();
            record.connectionId = context.getConnectionId();
            record.llmConfigId = context.getLlmConfigId();
            record.workspaceId = context.getWorkspaceId();
            record.schemaName = context.getSchemaName();
            record.tableNames = context.getTableNames() == null ? List.of() : List.copyOf(context.getTableNames());
            record.autoConfirm = context.isAutoConfirm();
            record.conversationId = context.getConversationId();
            RunnableConfig config = context.getRunnableConfig();
            if (config != null) record.checkpointId = config.checkPointId().orElse(null);
            return record;
        }

        AgentRunContext toContext() {
            RunnableConfig.Builder builder = RunnableConfig.builder().threadId(threadId);
            if (checkpointId != null && !checkpointId.isBlank()) builder.checkPointId(checkpointId);
            AgentRunContext context = new AgentRunContext(threadId, userId, connectionId,
                    llmConfigId, workspaceId, tableNames, schemaName, autoConfirm, builder.build());
            context.setConversationId(conversationId);
            return context;
        }
    }
}
