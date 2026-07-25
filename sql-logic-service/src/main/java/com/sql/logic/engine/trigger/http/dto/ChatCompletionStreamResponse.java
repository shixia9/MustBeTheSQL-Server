package com.sql.logic.engine.trigger.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionStreamResponse {
    private String id;
    private String object = "chat.completion.chunk";
    private Long created;
    private String model;
    private List<Choice> choices;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Choice {
        private Integer index;
        private Delta delta;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Delta {
        private String role;
        private String content;
    }
}
