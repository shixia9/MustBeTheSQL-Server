package com.sql.logic.engine.trigger.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {
    private String model;
    private List<Message> messages;
    private Boolean stream = false;
    private Double temperature;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    @JsonProperty("top_p")
    private Double topP;
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;
    @JsonProperty("presence_penalty")
    private Double presencePenalty;
    private Object stop;
    private String user;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private Object content;
        private String name;
        @JsonProperty("tool_calls")
        private Object toolCalls;
        @JsonProperty("tool_call_id")
        private String toolCallId;
    }
}
