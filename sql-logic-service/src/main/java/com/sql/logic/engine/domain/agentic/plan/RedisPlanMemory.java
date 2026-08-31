package com.sql.logic.engine.domain.agentic.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Redis-backed plan memory used by the agentic graph.
 *
 * <p>Graph checkpoints are persisted in Redis, so the plan that the Manager
 * consults must be persisted there as well; otherwise a resume handled by a
 * different service instance would have a checkpoint but no executable plan.</p>
 */
public class RedisPlanMemory implements PlanMemory {

    private static final String KEY_PREFIX = "sql:agentic:plan:";
    private static final String LOCK_PREFIX = "sql:agentic:plan:lock:";
    public static final Duration TTL = Duration.ofHours(2);
    public static final int MAX_PLAN_BYTES = 512 * 1024;

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    public RedisPlanMemory(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.objectMapper.findAndRegisterModules();
    }

    @Override
    public void savePlan(String convId, List<PlanStep> steps) {
        withLock(convId, ignored -> write(convId, steps));
    }

    @Override
    public List<PlanStep> getByConvId(String convId) {
        return withLockResult(convId, ignored -> read(convId));
    }

    @Override
    public List<PlanStep> getTodoPlans(String convId) {
        return withLockResult(convId, ignored -> read(convId).stream()
                .filter(p -> p.getStatus() == PlanStatus.TODO || p.getStatus() == PlanStatus.FAILED)
                .toList());
    }

    @Override
    public List<PlanStep> getByConvIdAndNum(String convId, List<Integer> taskNums) {
        Set<Integer> wanted = new HashSet<>(taskNums == null ? List.of() : taskNums);
        return withLockResult(convId, ignored -> read(convId).stream()
                .filter(p -> wanted.contains(p.getSerialNumber()))
                .toList());
    }

    @Override
    public void completeTask(String convId, int taskNum, String result) {
        withLock(convId, ignored -> updateOne(convId, taskNum, step -> {
            step.setStatus(PlanStatus.COMPLETED);
            step.setResult(result);
        }));
    }

    @Override
    public void updateTask(String convId, int taskNum, PlanStatus status,
                           int retryTimes, String agent, String result) {
        withLock(convId, ignored -> updateOne(convId, taskNum, step -> {
            step.setStatus(status);
            step.setRetryTimes(retryTimes);
            if (agent != null && !agent.isBlank()) step.setAgent(agent);
            if (result != null) step.setResult(result);
        }));
    }

    @Override
    public void removeByConvId(String convId) {
        withLock(convId, ignored -> bucket(convId).delete());
    }

    private void updateOne(String convId, int taskNum, Consumer<PlanStep> update) {
        List<PlanStep> steps = read(convId);
        for (PlanStep step : steps) {
            if (step.getSerialNumber() == taskNum) {
                update.accept(step);
                break;
            }
        }
        write(convId, steps);
    }

    private List<PlanStep> read(String convId) {
        if (convId == null || convId.isBlank()) return List.of();
        String json = bucket(convId).get();
        if (json == null || json.isBlank()) return List.of();
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<PlanStep>>() {}));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read agentic plan from Redis", e);
        }
    }

    private void write(String convId, List<PlanStep> steps) {
        if (convId == null || convId.isBlank()) return;
        try {
            String json = objectMapper.writeValueAsString(steps == null ? List.of() : steps);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PLAN_BYTES) {
                throw new IllegalStateException("Agentic plan exceeds the Redis size limit");
            }
            bucket(convId).set(json, TTL);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write agentic plan to Redis", e);
        }
    }

    private RBucket<String> bucket(String convId) {
        return redisson.getBucket(KEY_PREFIX + convId);
    }

    private RLock lock(String convId) {
        return redisson.getLock(LOCK_PREFIX + convId);
    }

    private void withLock(String convId, Consumer<Void> action) {
        RLock lock = lock(convId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("Timed out acquiring plan lock for " + convId);
            action.accept(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted acquiring plan lock for " + convId, e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private <T> T withLockResult(String convId, Function<Void, T> action) {
        RLock lock = lock(convId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("Timed out acquiring plan lock for " + convId);
            return action.apply(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted acquiring plan lock for " + convId, e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
