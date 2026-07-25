package com.sql.logic.engine.trigger.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionResponse {
    private String id;
    private String object = "chat.completion";
    private Long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Choice {
        private Integer index;
        private Message message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
