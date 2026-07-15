package com.sql.logic.engine.domain.agent.ha;

public final class HaConstants {

    private HaConstants() {}

    public static final double CIRCUIT_BREAKER_ERROR_RATE_THRESHOLD = 0.5;
    public static final int CIRCUIT_BREAKER_MIN_REQUEST_COUNT = 10;
    public static final int CIRCUIT_BREAKER_COOLDOWN_SECONDS = 30;
    public static final int CIRCUIT_BREAKER_CONSECUTIVE_FAILURE_THRESHOLD = 3;

    public static final double SMART_WEIGHT_SUCCESS_RATE = 0.4;
    public static final double SMART_WEIGHT_LATENCY = 0.4;
    public static final double SMART_WEIGHT_LOAD = 0.2;

    public static final long METRICS_WINDOW_MINUTES = 5;
    public static final int AFFINITY_MAX_ENTRIES = 10000;
    public static final int AFFINITY_EXPIRE_MINUTES = 60;

    public static final String AFFINITY_RBUCKET_KEY_PREFIX = "sql-logic:agent:ha:affinity:";
}
