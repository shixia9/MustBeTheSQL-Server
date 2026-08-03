package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.ScheduledRun;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface ScheduledRunDao extends BaseMapper<ScheduledRun> {

    @Select("SELECT * FROM scheduled_run WHERE task_id = #{taskId} ORDER BY started_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<ScheduledRun> selectByTask(@Param("taskId") Long taskId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT * FROM scheduled_run WHERE id = #{runId}")
    ScheduledRun selectByRunId(@Param("runId") Long runId);

    @Select("SELECT COUNT(*) FROM scheduled_run WHERE task_id = #{taskId}")
    long countByTask(@Param("taskId") Long taskId);

    /**
     * Finalize stale {@code running} runs as {@code failed}.
     *
     * <p>The {@code startedBefore} cutoff SCOPES the update to runs old enough that they
     * cannot still be legitimately executing — this is critical for multi-instance
     * deployments: a global {@code WHERE status='running'} would mark a SIBLING instance's
     * actively-running rows as failed on every restart. The cutoff should be larger than
     * the maximum possible run timeout (caller passes {@code 2 * default timeout}).
     */
    @Update("UPDATE scheduled_run SET status = 'failed', error_message = #{msg}, finished_at = NOW() " +
            "WHERE status = 'running' AND started_at < #{startedBefore}")
    int markStaleRunningAsFailed(@Param("msg") String msg, @Param("startedBefore") Date startedBefore);

    @Delete("DELETE FROM scheduled_run WHERE task_id = #{taskId}")
    int deleteByTask(@Param("taskId") Long taskId);
}
