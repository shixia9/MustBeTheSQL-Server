-- V018: Index to accelerate the schedule poller's due-task lookup.
-- selectDueEnabled filters on status = 1 AND next_run_time <= now; without this
-- index the poller performs a full table scan every cycle. Additive, safe.
-- Plain CREATE INDEX (no IF NOT EXISTS): MySQL < 8.0 does not support IF NOT EXISTS
-- for CREATE INDEX, and V018 runs exactly once via Flyway.
CREATE INDEX idx_sched_due ON scheduled_task (status, next_run_time);
