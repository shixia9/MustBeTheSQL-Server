package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.Skill;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for the {@link Skill} entity.
 * Custom queries are expressed via {@code LambdaQueryWrapper} in
 * {@code SkillCatalogService}; only {@link BaseMapper} defaults are needed here.
 */
@Mapper
public interface SkillDao extends BaseMapper<Skill> {
}
