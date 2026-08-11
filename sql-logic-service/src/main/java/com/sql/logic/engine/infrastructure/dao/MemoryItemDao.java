package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.MemoryItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface MemoryItemDao extends BaseMapper<MemoryItem> {

    /**
     * Decay importance of active memories not updated within {@code days} days by
     * multiplying by {@code factor}, floored at {@code floor} so a memory never
     * decays to nothing purely from inactivity. The {@code update_time} column is
     * deliberately NOT touched here — staleness is measured against the last
     * recall/write, not the last decay, so unrewarded memories keep decaying each
     * run while frequently-recalled ones (whose update_time is bumped on recall)
     * are exempt. See {@code MemoryDomainService#touchRecalled}.
     */
    @Update("UPDATE memory_item SET importance = GREATEST(importance * #{factor}, #{floor}) " +
            "WHERE status = 1 AND update_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int decayStaleMemories(@Param("factor") BigDecimal factor,
                           @Param("floor") BigDecimal floor,
                           @Param("days") int days);

    /**
     * Soft-delete (archive) active memories whose importance has decayed below
     * {@code threshold} and that have not been touched within {@code days} days.
     * Archived memories (status=0) are filtered out by
     * {@code MemoryDomainService#searchRelevant}, so this is a safe, reversible
     * cleanup — re-activating a memory only requires flipping status back to 1.
     */
    @Update("UPDATE memory_item SET status = 0 " +
            "WHERE status = 1 AND importance < #{threshold} " +
            "AND update_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int archiveLowValueMemories(@Param("threshold") BigDecimal threshold,
                                @Param("days") int days);

    /**
     * Bump {@code update_time} to NOW() for the given memory ids — marks them as
     * recently recalled so the decay pass above skips them ("frequently-recalled
     * memories are preserved"). Called by {@code MemoryDomainService} after a
     * successful recall. No-op for an empty id list.
     */
    @Update("<script>" +
            "UPDATE memory_item SET update_time = NOW() WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int touchUpdateTime(@Param("ids") List<Long> ids);
}