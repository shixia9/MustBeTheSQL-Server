package com.sql.logic.engine.domain.agent;

/**
 * Utility methods for safe state value coercion across graph nodes.
 * <p>
 * The StateGraph engine may serialize/deserialize the OverAllState between
 * node transitions. A {@link Long} stored in the initial state can become an
 * {@link Integer} after JSON round-trip. This utility provides type-safe
 * extraction that handles all common Number subclasses.
 */
public final class AgentStateUtil {

    private AgentStateUtil() {}

    /**
     * Safely extract a Long value from the state, handling {@link Long},
     * {@link Integer}, and any other {@link Number} subclass that may arise
     * from JSON deserialization between graph node transitions.
     *
     * @param obj the raw state value (may be null)
     * @return the Long value, or null if obj is null or not a Number
     */
    public static Long toLong(Object obj) {
        if (obj instanceof Long) {
            return (Long) obj;
        }
        if (obj instanceof Integer) {
            return ((Integer) obj).longValue();
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return null;
    }
}