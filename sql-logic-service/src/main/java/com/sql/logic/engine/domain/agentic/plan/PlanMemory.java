package com.sql.logic.engine.domain.agentic.plan;

import java.util.List;

/**
 * Plan storage interface.
 */
public interface PlanMemory {

    void savePlan(String convId, List<PlanStep> steps);

    List<PlanStep> getByConvId(String convId);

    List<PlanStep> getTodoPlans(String convId);

    List<PlanStep> getByConvIdAndNum(String convId, List<Integer> taskNums);

    void completeTask(String convId, int taskNum, String result);

    void updateTask(String convId, int taskNum, PlanStatus status,
                    int retryTimes, String agent, String result);

    void removeByConvId(String convId);
}
