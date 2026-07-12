package com.sql.logic.engine.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ConversationDetailDao extends BaseMapper<ConversationDetail> {

    /** Get the most recent user input for each conversation (used for list preview). */
    @Select("<script>" +
            "SELECT cd.conversation_id, cd.user_input, cd.create_time " +
            "FROM conversation_detail cd " +
            "INNER JOIN (" +
            "  SELECT conversation_id, MAX(create_time) AS max_time " +
            "  FROM conversation_detail WHERE conversation_id IN " +
            "  <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "  GROUP BY conversation_id" +
            ") latest ON cd.conversation_id = latest.conversation_id AND cd.create_time = latest.max_time" +
            "</script>")
    List<Map<String, Object>> selectLastMessages(@Param("ids") List<Long> conversationIds);

    /** Count details per conversation (used for list preview). */
    @Select("<script>" +
            "SELECT conversation_id, COUNT(*) AS cnt " +
            "FROM conversation_detail WHERE conversation_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " GROUP BY conversation_id" +
            "</script>")
    List<Map<String, Object>> countByConversationIds(@Param("ids") List<Long> conversationIds);
}
