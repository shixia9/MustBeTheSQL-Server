package com.sql.logic.engine.domain.agent.core;

import com.sql.logic.engine.infrastructure.llm.OpenAILLMStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiAgentFactory {

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String defaultBaseUrl;

    public AiAgent createOpenAiAgent(String apiKey, String baseUrl) {
        // Manually build OpenAiApi and ChatModel for specific user
        String finalBaseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : defaultBaseUrl;
        // OpenAiApi openAiApi = new OpenAiApi(defaultBaseUrl, apiKey);
        OpenAiApi openAiApi = OpenAiApi.builder()
                                    .baseUrl(finalBaseUrl)
                                    .apiKey(apiKey)
                                    .build();
        
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature((double) 0.2f)
                .build();
                
        // OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, options);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                                            .openAiApi(openAiApi)
                                            .defaultOptions(options)
                                            .build();
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        
        OpenAILLMStrategy strategy = new OpenAILLMStrategy(chatClient.mutate());
        return new SqlAiAgentImpl(strategy);
    }

    public AiAgent createDefaultAgent(ChatClient.Builder defaultChatClientBuilder) {
        OpenAILLMStrategy strategy = new OpenAILLMStrategy(defaultChatClientBuilder);
        return new SqlAiAgentImpl(strategy);
    }
}