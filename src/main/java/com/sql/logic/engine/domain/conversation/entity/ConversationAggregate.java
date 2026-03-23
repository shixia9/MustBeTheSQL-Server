package com.sql.logic.engine.domain.conversation.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationAggregate {
    private Long id;
    private Long userId;
    private String title;
    private Long llmStrategyId;
    private Date createTime;
    private Date updateTime;
    private List<ConversationDetailEntity> details;
}
