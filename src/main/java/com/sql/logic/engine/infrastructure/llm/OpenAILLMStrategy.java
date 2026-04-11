package com.sql.logic.engine.infrastructure.llm;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service("openAiStrategy")
public class OpenAILLMStrategy implements LLMStrategy {

    private final ChatClient chatClient;

    public OpenAILLMStrategy(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public Flux<String> generateSqlStream(String promptStr) {
        Prompt prompt = new Prompt(promptStr);
        
        return Flux.defer(() -> {
            JsonStreamParser parser = new JsonStreamParser();
            return chatClient.prompt(prompt)
                    .stream()
                    .content()
                    .flatMapIterable(chunk -> parser.processChunk(chunk))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(parser.processComplete())));
        });
    }

    @Override
    public String generateSql(String promptStr) {
        Prompt prompt = new Prompt(promptStr);
        return chatClient.prompt(prompt)
                .call()
                .content();
    }
}
