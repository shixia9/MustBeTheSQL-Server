package com.sql.logic.engine.domain.agent.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class AiAgentFactory {

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String defaultOpenAiBaseUrl;

    @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com}")
    private String defaultAnthropicBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    // Cache the ObjectMapper with NON_EMPTY setting
    private volatile ObjectMapper nonEmptyObjectMapper;

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
     */
    public LLMStrategy createLLMStrategy(ProviderType providerType, String apiKey, String baseUrl, String modelName) {
        return switch (providerType) {
            case OPENAI_COMPATIBLE -> createOpenAiStrategy(apiKey, baseUrl, modelName);
            case ANTHROPIC -> createAnthropicStrategy(apiKey, baseUrl, modelName);
        };
    }

    /**
     * Create the system default LLMStrategy directly (bypassing auto-configured builder).
     * This ensures the OpenAiApi uses a RestClient with NON_EMPTY ObjectMapper,
     * preventing "extra_body": {} from being serialized to API requests.
     */
    public LLMStrategy createDefaultSystemStrategy() {
        return createOpenAiStrategy(defaultApiKey, defaultOpenAiBaseUrl, null);
    }

    /**
     * Create an OpenAI-compatible LLMStrategy with custom credentials.
     * Uses a custom RestClient with NON_EMPTY ObjectMapper to prevent
     * empty extraBody serialization (fixes HTTP 400 on OpenAI-compatible APIs).
     * <p>
     * Also configures connect/read timeouts via JDK HttpClient to prevent
     * ClosedChannelException on unstable networks.
     */
    private OpenAILLMStrategy createOpenAiStrategy(String apiKey, String baseUrl, String modelName) {
        String finalBaseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : defaultOpenAiBaseUrl;

        // Build a JDK HttpClient with explicit connect timeout to prevent
        // ClosedChannelException when connecting to API proxies on unstable networks.
        // JDK HttpClient defaults can be too aggressive; 30s connect / 120s read is safe for LLM calls.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(120));

        // Custom RestClient with NON_EMPTY ObjectMapper — this is the fix for
        // "extra_body": {} being sent in the ChatCompletionRequest JSON body.
        // The ChatCompletionRequest Record's all-args constructor replaces null
        // extraBody with an empty HashMap, and default Jackson serializes it.
        RestClient.Builder restClientBuilder = RestClient.builder()
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(0, new MappingJackson2HttpMessageConverter(getNonEmptyObjectMapper()));
                })
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(finalBaseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
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

    /**
     * Lazy-initialize the ObjectMapper with NON_EMPTY inclusion.
     * This prevents "extra_body": {} from being serialized by bypassing
     * the default serialization which always includes empty Maps.
     */
    private ObjectMapper getNonEmptyObjectMapper() {
        if (nonEmptyObjectMapper == null) {
            synchronized (this) {
                if (nonEmptyObjectMapper == null) {
                    nonEmptyObjectMapper = new ObjectMapper();
                    nonEmptyObjectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
                }
            }
        }
        return nonEmptyObjectMapper;
    }
}