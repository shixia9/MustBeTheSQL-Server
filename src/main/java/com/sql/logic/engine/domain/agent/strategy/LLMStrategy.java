package com.sql.logic.engine.domain.agent.strategy;

import reactor.core.publisher.Flux;

public interface LLMStrategy {
    
    /**
     * 流式生成SQL
     * @param prompt 提示词
     * @return 流式返回生成的SQL
     */
    Flux<String> generateSqlStream(String prompt);

    /**
     * 非流式生成SQL
     * @param prompt 提示词
     * @return 生成的SQL
     */
    String generateSql(String prompt);
}
