package com.sql.logic.engine.domain.sandbox.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import com.sql.logic.engine.infrastructure.dao.SandboxExecutionLogDao;
import com.sql.logic.engine.infrastructure.po.SandboxExecutionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Audit trail for sandbox code executions.
 *
 * <p>Persists one {@link SandboxExecutionLog} row per execution — agent-driven or
 * manual — for traceability and debugging. Inserts are <b>fire-and-forget</b>
 * (async): a failure to audit never propagates to the caller and never breaks the
 * agent execution flow. stdout/stderr are truncated to {@link #MAX_OUTPUT_CHARS}
 * characters before insert to bound row size.
 *
 * <p>UserId is resolved best-effort from the sa-token session
 * ({@link StpUtil#getLoginId()}); when no session is bound to the current thread
 * (e.g. async agent dispatch) the field is left null.
 */
@Service
public class SandboxAuditService {

    private static final Logger log = LoggerFactory.getLogger(SandboxAuditService.class);

    /** Max characters of stdout/stderr persisted per row. */
    static final int MAX_OUTPUT_CHARS = 10_000;

    private final SandboxExecutionLogDao dao;

    public SandboxAuditService(SandboxExecutionLogDao dao) {
        this.dao = dao;
    }

    /**
     * Asynchronously persist an execution audit record. Never throws — audit
     * failures are logged and swallowed.
     *
     * @param threadId  conversation thread id (or manual-exec token)
     * @param sessionId underlying sandbox session id (may be null for legacy)
     * @param language  execution language
     * @param runtime   runtime id ("docker" / "local" / "legacy")
     * @param code      the original source code (before stdin shim)
     * @param result    the execution result
     */
    public void recordAsync(String threadId, String sessionId, String language,
                            String runtime, String code, ExecutionResult result) {
        if (dao == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                SandboxExecutionLog row = new SandboxExecutionLog();
                row.setThreadId(threadId);
                row.setUserId(currentUserId());
                row.setSessionId(sessionId);
                row.setLanguage(language);
                row.setRuntime(runtime);
                row.setCode(code);
                row.setStdout(truncate(result != null ? result.stdout() : null, MAX_OUTPUT_CHARS));
                row.setStderr(truncate(result != null ? result.stderr() : null, MAX_OUTPUT_CHARS));
                row.setExitCode(result != null ? result.exitCode() : null);
                row.setStatus(result != null && result.status() != null
                        ? result.status().value() : "error");
                row.setDurationMs(result != null ? result.durationMs() : null);
                row.setTimedOut(result != null && result.timedOut() ? 1 : 0);
                row.setCreatedAt(LocalDateTime.now());
                dao.insert(row);
            } catch (Exception e) {
                log.warn("[SandboxAudit] Failed to persist audit row for thread {}: {}",
                        threadId, e.getMessage());
            }
        });
    }

    /** Best-effort current user id from sa-token; null when no session is bound. */
    private static Long currentUserId() {
        try {
            Object id = StpUtil.getLoginId();
            if (id == null) {
                return null;
            }
            String s = id.toString();
            return s.isBlank() ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
