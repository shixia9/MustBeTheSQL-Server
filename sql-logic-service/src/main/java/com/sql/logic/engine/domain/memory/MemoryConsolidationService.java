package com.sql.logic.engine.domain.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.infrastructure.dao.MemoryItemDao;
import com.sql.logic.engine.infrastructure.po.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Lightweight background memory consolidation.
 * <p>
 * Runs daily and performs two cheap, reversible operations against
 * {@code memory_item}:
 * <ol>
 *   <li><b>Importance decay</b> — active memories not recalled/updated within
 *       {@link #DECAY_DAYS} days have their importance multiplied by
 *       {@link #DECAY_FACTOR} (floored at {@link #DECAY_FLOOR}), so stale
 *       preferences gradually lose recall weight. Frequently-recalled memories
 *       are exempt because {@link MemoryDomainService#searchRelevant} bumps
 *       {@code update_time} on every hit.</li>
 *   <li><b>Low-value archival</b> — active memories whose importance has decayed
 *       below {@link #ARCHIVE_THRESHOLD} and that have been untouched for
 *       {@link #ARCHIVE_DAYS} days are soft-deleted (status=0). They disappear
 *       from recall but remain in the table for audit / re-activation.</li>
 * </ol>
 *
 * <p>A memory count gate ({@link #MIN_MEMORIES}) skips the pass for users/tables
 * with little data, avoiding wasted scans. All operations are batch SQL updates —
 * no per-row LLM calls — so the cost is negligible even at scale.
 */
@Service
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);

    /** Memories older than this (in days, by update_time) decay each run. */
    static final int DECAY_DAYS = 7;
    /** Multiplicative decay factor applied each run (0.9 = lose 10%). */
    static final BigDecimal DECAY_FACTOR = new BigDecimal("0.9");
    /** Hard floor so inactivity never zeroes a memory outright. */
    static final BigDecimal DECAY_FLOOR = new BigDecimal("0.1");

    /** Importance below this (after decay) marks a memory as low-value. */
    static final BigDecimal ARCHIVE_THRESHOLD = new BigDecimal("0.2");
    /** Untouched duration (days) required before a low-value memory is archived. */
    static final int ARCHIVE_DAYS = 30;

    /** Skip the pass entirely when fewer than this many active memories exist. */
    static final long MIN_MEMORIES = 50L;

    private final MemoryItemDao memoryItemDao;

    public MemoryConsolidationService(MemoryItemDao memoryItemDao) {
        this.memoryItemDao = memoryItemDao;
    }

    /**
     * Daily consolidation at 03:00 server time. Fixed-delay fallback is avoided
     * in favour of an explicit cron so behaviour is predictable across restarts.
     * The method is cheap (two batch UPDATEs + one count); failures are logged
     * and swallowed so a transient DB hiccup never crashes the scheduler thread.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void consolidate() {
        try {
            long active = countActive();
            if (active < MIN_MEMORIES) {
                log.debug("[MemoryConsolidation] Skipped — only {} active memories (< {} gate)", active, MIN_MEMORIES);
                return;
            }

            int decayed = memoryItemDao.decayStaleMemories(DECAY_FACTOR, DECAY_FLOOR, DECAY_DAYS);
            int archived = memoryItemDao.archiveLowValueMemories(ARCHIVE_THRESHOLD, ARCHIVE_DAYS);

            log.info("[MemoryConsolidation] Pass complete: active={} decayed={} archived={}",
                    active, decayed, archived);
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] Pass failed: {}", e.getMessage());
        }
    }

    private long countActive() {
        QueryWrapper<MemoryItem> qw = new QueryWrapper<>();
        qw.eq("status", 1);
        return memoryItemDao.selectCount(qw);
    }
}
