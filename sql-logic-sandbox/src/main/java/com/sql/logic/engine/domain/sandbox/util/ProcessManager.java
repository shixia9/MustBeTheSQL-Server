package com.sql.logic.engine.domain.sandbox.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Process-tree management.
 *
 * <p>Uses the Java 9+ {@link ProcessHandle} API to recursively terminate a process
 * and all its descendants. The kill sequence is:
 * <ol>
 *   <li>Terminate all descendants (SIGTERM).</li>
 *   <li>Wait up to 3s for descendants to exit.</li>
 *   <li>Kill any survivors (SIGKILL).</li>
 *   <li>Terminate the parent.</li>
 *   <li>Wait up to 3s, then force-kill if still alive.</li>
 * </ol>
 */
public final class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);
    private static final long GRACE_WAIT_SECONDS = 3;

    private ProcessManager() {
    }

    /**
     * Kill a process and all its descendants.
     *
     * @param pid the root process id
     * @return true if the process was found and the kill sequence completed
     */
    public static boolean killProcessTree(long pid) {
        return ProcessHandle.of(pid).map(handle -> {
            try {
                // 1. Terminate all descendants first (children before parent).
                handle.descendants().forEach(ProcessHandle::destroy);

                // 2. Wait for descendants to exit gracefully.
                handle.descendants().forEach(d -> {
                    try {
                        d.onExit().get(GRACE_WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // Still alive — force kill below.
                    }
                });

                // 3. Force-kill any surviving descendants.
                handle.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);

                // 4. Terminate the parent.
                handle.destroy();

                // 5. Wait for parent, then force-kill if needed.
                try {
                    handle.onExit().get(GRACE_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    handle.destroyForcibly();
                }
                return true;
            } catch (Exception e) {
                log.warn("[ProcessManager] Failed to kill process tree for pid={}: {}", pid, e.getMessage());
                return false;
            }
        }).orElse(false);
    }

    /**
     * Kill a {@link Process} and its entire tree. Convenience over {@link #killProcessTree(long)}.
     */
    public static boolean killProcessTree(Process process) {
        if (process == null) {
            return false;
        }
        if (!process.isAlive()) {
            return true;
        }
        long pid = process.pid();
        // destroyForcibly as a fallback; killProcessTree handles descendants.
        killProcessTree(pid);
        process.destroyForcibly();
        return true;
    }
}
