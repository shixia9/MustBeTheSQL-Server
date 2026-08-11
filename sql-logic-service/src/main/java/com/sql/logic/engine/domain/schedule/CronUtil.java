package com.sql.logic.engine.domain.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Cron expression validation and next-fire-time computation.
 *
 * Replaces the brittle regex in the old ScheduledTaskAppService. Uses Spring's
 * {@link CronExpression} which both validates and computes the next trigger.
 *
 * Tolerates both 5-field Unix crontab ("* * * * *" = min hour dom mon dow) and
 * 6-field Quartz-style ("0 * * * * *" = sec min hour dom mon dow, often with
 * '?' in day fields). Spring CronExpression does not accept '?', so it is
 * translated to '*' before parsing.
 */
public final class CronUtil {

    private CronUtil() {}

    /**
     * Normalize a user-supplied cron string to a 6-field Spring CronExpression string.
     * - 6 fields: keep, translate '?' -> '*'.
     * - 5 fields: prepend '0' second, translate '?' -> '*'.
     * - other: return null (invalid).
     */
    public static String normalize(String cron) {
        if (cron == null) return null;
        String trimmed = cron.trim().replaceAll("\\s+", " ");
        String[] parts = trimmed.split(" ");
        String six;
        if (parts.length == 6) {
            six = trimmed;
        } else if (parts.length == 5) {
            six = "0 " + trimmed;
        } else {
            return null;
        }
        // Spring CronExpression does not accept '?'; Quartz uses it for day-of-month/day-of-week.
        return six.replace('?', '*');
    }

    /**
     * Validate a cron expression. Returns the normalized 6-field form, or throws
     * IllegalArgumentException if invalid.
     */
    public static String validate(String cron) {
        String normalized = normalize(cron);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid cron expression (expected 5 or 6 fields): " + cron);
        }
        try {
            CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron + " (" + e.getMessage() + ")");
        }
        return normalized;
    }

    /**
     * Compute the next fire time after {@code from} for the given cron and timezone.
     *
     * @param cron   raw user cron (5 or 6 field, may contain '?')
     * @param zoneId timezone name (e.g. "Asia/Shanghai"); null/blank → system default
     * @param from   reference date; null → now
     * @return the next fire time as a Date, or null if cron is invalid
     */
    public static Date nextRunTime(String cron, String zoneId, Date from) {
        String normalized = normalize(cron);
        if (normalized == null) return null;
        CronExpression expr;
        try {
            expr = CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ZoneId zone = resolveZone(zoneId);
        LocalDateTime ref = (from != null)
                ? LocalDateTime.ofInstant(from.toInstant(), zone)
                : LocalDateTime.now(zone);
        LocalDateTime next = expr.next(ref);
        if (next == null) return null;
        return Date.from(next.atZone(zone).toInstant());
    }

    /**
     * Resolve a timezone name to a ZoneId. Returns system default on null/blank or invalid.
     * (Use {@link #validateZone(String)} when you need strict validation that throws.)
     */
    public static ZoneId resolveZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(zoneId);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /**
     * Strictly validate a timezone name. Throws IllegalArgumentException if invalid.
     */
    public static void validateZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) return; // null = server default, allowed
        try {
            ZoneId.of(zoneId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid time_zone: " + zoneId);
        }
    }
}
