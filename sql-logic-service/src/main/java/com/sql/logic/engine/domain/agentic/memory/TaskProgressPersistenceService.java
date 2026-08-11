package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.AgentMemory;
import com.sql.logic.engine.domain.agentic.core.AgentMemory.TaskStatus;
import com.sql.logic.engine.infrastructure.dao.TaskProgressSnapshotDao;
import com.sql.logic.engine.infrastructure.po.TaskProgressSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists task progress snapshots to the database for durability across
 * context compression and agent restarts.
 * <p>
 * Writes are asynchronous (virtual threads) — failures are logged but
 * never block the main agent pipeline.
 */
public class TaskProgressPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressPersistenceService.class);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final TaskProgressSnapshotDao dao;

    public TaskProgressPersistenceService(TaskProgressSnapshotDao dao) {
        this.dao = dao;
    }

    /**
     * Persist a task progress entry asynchronously.
     */
    public void persistAsync(String convId, AgentMemory.TaskProgressEntry entry) {
        if (dao == null || convId == null) return;
        executor.submit(() -> persist(convId, entry));
    }

    /**
     * Persist a task progress entry synchronously.
     */
    public void persist(String convId, AgentMemory.TaskProgressEntry entry) {
        if (dao == null || convId == null) return;
        try {
            TaskProgressSnapshot snapshot = new TaskProgressSnapshot();
            snapshot.setConvId(convId);
            snapshot.setStepNumber(entry.step());
            snapshot.setAction(entry.action());
            snapshot.setPhase(entry.phase());
            snapshot.setStatus(entry.status() == TaskStatus.DONE ? "DONE" : "FAILED");
            snapshot.setSnapshot(entry.snapshot());
            snapshot.setCreateTime(LocalDateTime.now());
            dao.insert(snapshot);
        } catch (Exception e) {
            log.debug("Failed to persist task progress snapshot: {}", e.getMessage());
        }
    }
}
