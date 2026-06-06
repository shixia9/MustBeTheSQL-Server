package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("conversation_detail")
public class ConversationDetail {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String userInput;
    private String sqlOutput;
    private String executeResult;
    private Date createTime;
}
