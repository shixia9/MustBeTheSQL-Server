package com.sql.logic.engine.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.AgentExecutionStep;

@Mapper
public interface AgentExecutionStepDao extends BaseMapper<AgentExecutionStep> {
}
