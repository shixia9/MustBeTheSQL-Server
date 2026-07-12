package com.sql.logic.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.admin.po.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminUserDao extends BaseMapper<AdminUser> {

    @Select("SELECT * FROM admin_user WHERE user_id = #{userId} AND status = 1 LIMIT 1")
    AdminUser findByUserId(Long userId);
}
