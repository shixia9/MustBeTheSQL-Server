package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScheduledTaskDao extends BaseMapper<ScheduledTask> {
}
