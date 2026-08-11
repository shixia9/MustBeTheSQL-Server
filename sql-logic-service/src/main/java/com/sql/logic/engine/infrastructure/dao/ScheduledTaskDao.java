package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

@Mapper
public interface ScheduledTaskDao extends BaseMapper<ScheduledTask> {

    @Select("SELECT * FROM scheduled_task WHERE status = 1 AND next_run_time IS NOT NULL AND next_run_time <= #{now} ORDER BY next_run_time ASC")
    List<ScheduledTask> selectDueEnabled(Date now);

    @Select("SELECT * FROM scheduled_task WHERE status = 1")
    List<ScheduledTask> selectAllEnabled();
}
