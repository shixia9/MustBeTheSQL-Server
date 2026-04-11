package com.sql.logic.engine.domain.agent.strategy;

import reactor.core.publisher.Flux;

import java.util.function.Consumer;

public interface LLMStrategy {
    
    /**
     * 流式生成SQL
     * @param prompt 提示词
     * @param tokenUsageCallback token消耗回调
     * @return 流式返回生成的SQL
     */
    Flux<String> generateSqlStream(String prompt, Consumer<Integer> tokenUsageCallback);

    /**
     * 非流式生成SQL
     * @param prompt 提示词
     * @param tokenUsageCallback token消耗回调
     * @return 生成的SQL
     */
    String generateSql(String prompt, Consumer<Integer> tokenUsageCallback);
}
