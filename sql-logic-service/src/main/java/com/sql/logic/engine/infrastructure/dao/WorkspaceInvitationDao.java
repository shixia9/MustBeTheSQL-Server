package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.WorkspaceInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface WorkspaceInvitationDao extends BaseMapper<WorkspaceInvitation> {
    @Select("SELECT * FROM workspace_invitation WHERE token = #{token}")
    WorkspaceInvitation findByToken(String token);

    @Select("SELECT * FROM workspace_invitation WHERE workspace_id = #{workspaceId} ORDER BY create_time DESC")
    List<WorkspaceInvitation> findByWorkspaceId(Long workspaceId);
}
