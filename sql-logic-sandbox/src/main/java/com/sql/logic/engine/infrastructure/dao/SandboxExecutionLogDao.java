package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.SandboxExecutionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link SandboxExecutionLog}.
 */
@Mapper
public interface SandboxExecutionLogDao extends BaseMapper<SandboxExecutionLog> {
}
