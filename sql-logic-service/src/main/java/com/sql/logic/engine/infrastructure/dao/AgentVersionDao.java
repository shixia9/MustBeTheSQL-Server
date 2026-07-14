package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.AgentVersion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentVersionDao extends BaseMapper<AgentVersion> {

    @Select("SELECT * FROM agent_version WHERE agent_id = #{agentId} ORDER BY version_number DESC")
    List<AgentVersion> listByAgentId(Long agentId);

    @Select("SELECT COALESCE(MAX(version_number), 0) FROM agent_version WHERE agent_id = #{agentId}")
    int maxVersionNumber(Long agentId);

    @Delete("DELETE FROM agent_version WHERE agent_id = #{agentId} AND publish_time < #{cutoff}")
    int deleteOlderThan(Long agentId, java.util.Date cutoff);

    @Delete("DELETE FROM agent_version WHERE id = #{versionId} AND agent_id = #{agentId}")
    int deleteByIdAndAgent(Long versionId, Long agentId);
}
