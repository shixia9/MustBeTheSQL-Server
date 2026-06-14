package com.sql.logic.engine.domain.agent.core;

import com.sql.logic.engine.domain.agent.strategy.ProviderType;
import com.sql.logic.engine.infrastructure.llm.AnthropicLLMStrategy;
import com.sql.logic.engine.infrastructure.llm.OpenAILLMStrategy;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiAgentFactory {

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String defaultOpenAiBaseUrl;

    @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com}")
    private String defaultAnthropicBaseUrl;

    // ========================
    // Agent creation (one per user)
    // ========================

    /**
     * Create the system default agent using Spring AI's auto-configured ChatClient.Builder.
     */
    public AiAgent createDefaultAgent(ChatClient.Builder defaultChatClientBuilder) {
        OpenAILLMStrategy strategy = new OpenAILLMStrategy(defaultChatClientBuilder);
        return new SqlAiAgentImpl(strategy);
    }

    // ========================
    // LLMStrategy creation (one per config, pooled in LlmClientManager)
    // ========================

    /**
     * Create an LLMStrategy for a specific provider/config combination.
     * This is called by LlmClientManager and AiAgentWarmupRunner to pool
     * LLM client instances keyed by configId.
     */
    public LLMStrategy createLLMStrategy(ProviderType providerType, String apiKey, String baseUrl, String modelName) {
        return switch (providerType) {
            case OPENAI_COMPATIBLE -> createOpenAiStrategy(apiKey, baseUrl, modelName);
            case ANTHROPIC -> createAnthropicStrategy(apiKey, baseUrl, modelName);
        };
    }

    /**
     * Create an OpenAI-compatible LLMStrategy with custom credentials.
     */
    private OpenAILLMStrategy createOpenAiStrategy(String apiKey, String baseUrl, String modelName) {
        String finalBaseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : defaultOpenAiBaseUrl;

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(finalBaseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .temperature(0.2);
        if (modelName != null && !modelName.isBlank()) {
            optionsBuilder.model(modelName);
        }

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        return new OpenAILLMStrategy(chatClient.mutate());
    }

    /**
     * Create an Anthropic (Claude) LLMStrategy with custom credentials.
     */
    private AnthropicLLMStrategy createAnthropicStrategy(String apiKey, String baseUrl, String modelName) {
        String finalBaseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : defaultAnthropicBaseUrl;

        AnthropicApi anthropicApi = AnthropicApi.builder()
                .baseUrl(finalBaseUrl)
                .apiKey(apiKey)
                .build();

        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                .temperature(0.2);
        if (modelName != null && !modelName.isBlank()) {
            optionsBuilder.model(modelName);
        }

        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(optionsBuilder.build())
                .build();
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        return new AnthropicLLMStrategy(chatClient.mutate());
    }
}