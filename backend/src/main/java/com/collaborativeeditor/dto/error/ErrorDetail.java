package com.collaborativeeditor.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ErrorDetail {

    private String code;
    private String message;
    private String requestId;
    private Object details;

    public ErrorDetail() {
    }

    public ErrorDetail(String code, String message, String requestId, Object details) {
        this.code = code;
        this.message = message;
        this.requestId = requestId;
        this.details = details != null ? details : Map.of();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Object getDetails() {
        return details;
    }

    public void setDetails(Object details) {
        this.details = details;
    }
}

