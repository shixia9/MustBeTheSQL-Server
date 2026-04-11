package com.sql.logic.engine.infrastructure.llm;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service("openAiStrategy")
public class OpenAILLMStrategy implements LLMStrategy {

    private final ChatClient chatClient;

    public OpenAILLMStrategy(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public Flux<String> generateSqlStream(String promptStr, Consumer<Integer> tokenUsageCallback) {
        Prompt prompt = new Prompt(promptStr);
        
        return Flux.defer(() -> {
            JsonStreamParser parser = new JsonStreamParser();
            AtomicInteger maxTokens = new AtomicInteger(0);
            
            return chatClient.prompt(prompt)
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                            Integer totalTokens = response.getMetadata().getUsage().getTotalTokens();
                            if (totalTokens != null) {
                                maxTokens.set(Math.max(maxTokens.get(), totalTokens.intValue()));
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        if (maxTokens.get() > 0 && tokenUsageCallback != null) {
                            tokenUsageCallback.accept(maxTokens.get());
                        }
                    })
                    .flatMapIterable(response -> {
                        String chunk = response.getResult() != null && response.getResult().getOutput() != null ? response.getResult().getOutput().getText() : "";
                        return parser.processChunk(chunk);
                    })
                    .concatWith(Flux.defer(() -> Flux.fromIterable(parser.processComplete())));
        });
    }

    @Override
    public String generateSql(String promptStr, Consumer<Integer> tokenUsageCallback) {
        Prompt prompt = new Prompt(promptStr);
        var response = chatClient.prompt(prompt).call().chatResponse();
        
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Integer totalTokens = response.getMetadata().getUsage().getTotalTokens();
            if (totalTokens != null && totalTokens > 0 && tokenUsageCallback != null) {
                tokenUsageCallback.accept(totalTokens.intValue());
            }
        }
        
        return response != null && response.getResult() != null && response.getResult().getOutput() != null ? response.getResult().getOutput().getText() : "";
    }
}
