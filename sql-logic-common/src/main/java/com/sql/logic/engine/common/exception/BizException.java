package com.sql.logic.engine.common.exception;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final int code;
    private final Object data;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.data = null;
    }

    public BizException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}