package com.sql.logic.engine.domain.agent.strategy;

public enum ProviderType {
    OPENAI_COMPATIBLE("openAiStrategy"),
    ANTHROPIC("anthropicStrategy");

    private final String strategyBeanName;

    ProviderType(String strategyBeanName) {
        this.strategyBeanName = strategyBeanName;
    }

    public String getStrategyBeanName() {
        return strategyBeanName;
    }

    public static ProviderType fromString(String value) {
        if (value == null) return OPENAI_COMPATIBLE;
        return switch (value.toUpperCase()) {
            case "ANTHROPIC" -> ANTHROPIC;
            default -> OPENAI_COMPATIBLE;
        };
    }
}