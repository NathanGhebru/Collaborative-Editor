package com.collaborativeeditor.dto.error;

public class ErrorResponse {

    private ErrorDetail error;

    public ErrorResponse() {
    }

    public ErrorResponse(ErrorDetail error) {
        this.error = error;
    }

    public ErrorResponse(String code, String message, String requestId, Object details) {
        this.error = new ErrorDetail(code, message, requestId, details);
    }

    public ErrorDetail getError() {
        return error;
    }

    public void setError(ErrorDetail error) {
        this.error = error;
    }
}

