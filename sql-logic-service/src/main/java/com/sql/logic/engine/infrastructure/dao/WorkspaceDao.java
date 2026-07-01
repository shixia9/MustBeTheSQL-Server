package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface WorkspaceDao extends BaseMapper<Workspace> {
    @Select("SELECT w.* FROM workspace w INNER JOIN workspace_member wm ON w.id = wm.workspace_id WHERE wm.user_id = #{userId} AND w.status = 1 ORDER BY w.create_time DESC")
    List<Workspace> findByMemberUserId(Long userId);

    @Select("SELECT * FROM workspace WHERE owner_id = #{ownerId} AND status = 1 ORDER BY create_time DESC")
    List<Workspace> findByOwnerId(Long ownerId);
}
