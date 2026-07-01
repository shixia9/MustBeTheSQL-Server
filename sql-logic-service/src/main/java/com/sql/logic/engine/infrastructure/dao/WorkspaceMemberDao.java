package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.WorkspaceMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface WorkspaceMemberDao extends BaseMapper<WorkspaceMember> {
    @Select("SELECT * FROM workspace_member WHERE workspace_id = #{workspaceId} AND user_id = #{userId}")
    WorkspaceMember findByWorkspaceAndUser(Long workspaceId, Long userId);

    @Select("SELECT * FROM workspace_member WHERE workspace_id = #{workspaceId} ORDER BY FIELD(role, 'OWNER', 'ADMIN', 'MEMBER', 'VIEWER'), create_time ASC")
    List<WorkspaceMember> findByWorkspaceId(Long workspaceId);
}
