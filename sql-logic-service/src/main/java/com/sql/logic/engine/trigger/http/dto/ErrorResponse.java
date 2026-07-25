package com.sql.logic.engine.trigger.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private ErrorDetail error;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        private String message;
        private String type;
        private String param;
        private String code;
    }

    public static ErrorResponse of(String message, String type, String code) {
        ErrorResponse r = new ErrorResponse();
        ErrorDetail d = new ErrorDetail();
        d.message = message;
        d.type = type;
        d.code = code;
        r.error = d;
        return r;
    }
}
