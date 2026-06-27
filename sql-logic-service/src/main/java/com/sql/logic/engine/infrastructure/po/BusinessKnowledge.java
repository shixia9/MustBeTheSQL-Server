package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Phase 5 — user-managed business knowledge (glossary term or few-shot QA) scoped to
 * a user + their DB connection. Saved rows are embedded into the pgvector vector_store
 * (see {@code KnowledgeEmbeddingService}). The {@code vectorType} discriminator selects
 * which retrieval channel the row feeds: GLOSSARY_KNOWLEDGE or QUESTION_KNOWLEDGE.
 */
@Data
@TableName("business_knowledge")
public class BusinessKnowledge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long connectionId;
    /** GLOSSARY_KNOWLEDGE | QUESTION_KNOWLEDGE */
    private String vectorType;
    /** Glossary term (glossary rows only). */
    private String term;
    /** Glossary description / definition (glossary rows only). */
    private String description;
    /** FAQ question (question rows only). */
    private String question;
    /** FAQ answer, e.g. a reference SQL (question rows only). */
    private String answer;
    /** Glossary synonyms, comma-separated (glossary rows only). */
    private String synonyms;
    /** 0 = pending re-embed (embedding failed), 1 = active. */
    private Integer status;
    private Date createTime;
    private Date updateTime;
}