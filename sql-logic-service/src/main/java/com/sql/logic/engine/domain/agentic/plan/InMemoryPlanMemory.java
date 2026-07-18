package com.sql.logic.engine.domain.agentic.plan;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory PlanMemory implementation backed by ConcurrentHashMap.
 * Each conversation (convId) holds an ordered list of PlanSteps.
 */
public class InMemoryPlanMemory implements PlanMemory {

    private final ConcurrentHashMap<String, List<PlanStep>> store = new ConcurrentHashMap<>();

    @Override
    public void savePlan(String convId, List<PlanStep> steps) {
        store.put(convId, new ArrayList<>(steps));
    }

    @Override
    public List<PlanStep> getByConvId(String convId) {
        return store.getOrDefault(convId, List.of());
    }

    @Override
    public List<PlanStep> getTodoPlans(String convId) {
        List<PlanStep> plans = store.get(convId);
        if (plans == null) return List.of();
        return plans.stream()
                .filter(p -> p.getStatus() == PlanStatus.TODO || p.getStatus() == PlanStatus.FAILED)
                .collect(Collectors.toList());
    }

    @Override
    public List<PlanStep> getByConvIdAndNum(String convId, List<Integer> taskNums) {
        List<PlanStep> plans = store.get(convId);
        if (plans == null) return List.of();
        Set<Integer> numSet = new HashSet<>(taskNums);
        return plans.stream()
                .filter(p -> numSet.contains(p.getSerialNumber()))
                .collect(Collectors.toList());
    }

    @Override
    public void completeTask(String convId, int taskNum, String result) {
        List<PlanStep> plans = store.get(convId);
        if (plans == null) return;
        for (PlanStep p : plans) {
            if (p.getSerialNumber() == taskNum) {
                p.setStatus(PlanStatus.COMPLETED);
                p.setResult(result);
                break;
            }
        }
    }

    @Override
    public void updateTask(String convId, int taskNum, PlanStatus status,
                           int retryTimes, String agent, String result) {
        List<PlanStep> plans = store.get(convId);
        if (plans == null) return;
        for (PlanStep p : plans) {
            if (p.getSerialNumber() == taskNum) {
                p.setStatus(status);
                p.setRetryTimes(retryTimes);
                if (agent != null && !agent.isBlank()) p.setAgent(agent);
                if (result != null) p.setResult(result);
                break;
            }
        }
    }

    @Override
    public void removeByConvId(String convId) {
        store.remove(convId);
    }
}
