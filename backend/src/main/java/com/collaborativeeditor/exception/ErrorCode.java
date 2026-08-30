package com.collaborativeeditor.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request payload."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required or invalid."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded."),

    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already registered."),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email is already registered."),
    INVALID_USERNAME(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid username format."),
    INVALID_EMAIL(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid email format."),
    WEAK_PASSWORD(HttpStatus.UNPROCESSABLE_ENTITY, "Password does not meet requirements."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "User account is disabled."),

    REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "Refresh token cookie missing."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Invalid refresh token."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token expired."),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked."),

    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Document not found."),
    DOCUMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "Document access denied."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred.");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}

