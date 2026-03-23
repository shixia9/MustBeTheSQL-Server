package com.sql.logic.engine.domain.conversation.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationDetailEntity {
    private Long id;
    private Long conversationId;
    private String userInput;
    private String sqlOutput;
    private String executeResult;
    private Date createTime;
}
