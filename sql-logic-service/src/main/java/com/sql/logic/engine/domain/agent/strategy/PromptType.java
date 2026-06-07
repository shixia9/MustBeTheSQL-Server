package com.sql.logic.engine.domain.agent.strategy;

/**
 * Enumerates the types of AI-powered SQL operations.
 * Inspired by Chat2DB's PromptType design, this enables the system
 * to generate different prompt templates for different operation types.
 */
public enum PromptType {

    /**
     * Natural language to SQL query generation.
     */
    NL_TO_SQL,

    /**
     * Explain a SQL query in natural language.
     */
    SQL_EXPLAIN,

    /**
     * Optimize a SQL query for better performance.
     */
    SQL_OPTIMIZE,

    /**
     * Convert a SQL query between database dialects.
     */
    SQL_CONVERT
}