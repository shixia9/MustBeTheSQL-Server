package com.sql.logic.engine.infrastructure.llm;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Service("anthropicStrategy")
public class AnthropicLLMStrategy implements LLMStrategy {

    private final ChatClient chatClient;

    public AnthropicLLMStrategy(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public Flux<String> generateSqlStream(String promptStr, BiConsumer<Integer, String> tokenAndSqlCallback) {
        Prompt prompt = new Prompt(promptStr);

        return Flux.defer(() -> {
            JsonStreamParser parser = new JsonStreamParser();
            AtomicInteger maxTokens = new AtomicInteger(0);

            Flux<String> streamContent = chatClient.prompt(prompt)
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
                    .flatMapIterable(response -> {
                        String chunk = response.getResult() != null && response.getResult().getOutput() != null
                                ? response.getResult().getOutput().getText() : "";
                        return parser.processChunk(chunk);
                    });

            Flux<String> completeContent = Flux.fromIterable(parser.processComplete());

            return Flux.concat(streamContent, completeContent)
                    .doFinally(signalType -> {
                        if (tokenAndSqlCallback != null) {
                            tokenAndSqlCallback.accept(maxTokens.get(), parser.getExtractedSql());
                        }
                    });
        });
    }

    @Override
    public String generateSql(String promptStr, BiConsumer<Integer, String> tokenAndSqlCallback) {
        Prompt prompt = new Prompt(promptStr);
        var response = chatClient.prompt(prompt).call().chatResponse();

        String generatedContent = response != null && response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText() : "";

        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Integer totalTokens = response.getMetadata().getUsage().getTotalTokens();
            if (totalTokens != null && totalTokens > 0 && tokenAndSqlCallback != null) {
                tokenAndSqlCallback.accept(totalTokens.intValue(), generatedContent);
            }
        }

        return generatedContent;
    }
}