package com.sql.logic.engine.domain.schedule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Configuration for the schedule module execution engine.
 *
 * Defines a dedicated {@link ThreadPoolTaskScheduler} so scheduled-task runner
 * dispatch never blocks the poller thread and is isolated from other Spring
 * schedulers (e.g. MemoryConsolidationService).
 */
@Configuration
public class ScheduleConfig {

    @Bean(name = "scheduleExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler scheduleExecutor(
            @Value("${schedule.executor-pool-size:4}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
