package com.collaborativeeditor.exception;

import com.collaborativeeditor.dto.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        String requestId = UUID.randomUUID().toString();
        ErrorCode code = ex.getErrorCode();
        log.warn("API Exception [{}]: {} - {}", requestId, code.name(), ex.getMessage());
        ErrorResponse response = new ErrorResponse(code.name(), ex.getMessage(), requestId, ex.getDetails());
        return new ResponseEntity<>(response, code.getHttpStatus());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> fieldErrors = new HashMap<>();
        ErrorCode primaryErrorCode = ErrorCode.INVALID_REQUEST;

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
            if ("username".equalsIgnoreCase(error.getField())) {
                primaryErrorCode = ErrorCode.INVALID_USERNAME;
            } else if ("email".equalsIgnoreCase(error.getField())) {
                primaryErrorCode = ErrorCode.INVALID_EMAIL;
            } else if ("password".equalsIgnoreCase(error.getField())) {
                primaryErrorCode = ErrorCode.WEAK_PASSWORD;
            } else if ("title".equalsIgnoreCase(error.getField())) {
                primaryErrorCode = ErrorCode.INVALID_TITLE;
            } else if ("initialContent".equalsIgnoreCase(error.getField())) {
                primaryErrorCode = ErrorCode.DOCUMENT_TOO_LARGE;
            } else if ("role".equalsIgnoreCase(error.getField())) {
                primaryErrorCode = ErrorCode.INVALID_ROLE;
            }
        }

        log.warn("Validation failure [{}]: fieldErrors={}", requestId, fieldErrors);
        ErrorResponse response = new ErrorResponse(
                primaryErrorCode.name(),
                primaryErrorCode.getDefaultMessage(),
                requestId,
                fieldErrors
        );
        return new ResponseEntity<>(response, primaryErrorCode.getHttpStatus());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadableException(HttpMessageNotReadableException ex) {
        String requestId = UUID.randomUUID().toString();
        log.warn("Malformed JSON request [{}]: {}", requestId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                ErrorCode.INVALID_REQUEST.name(),
                "Malformed or unparseable JSON payload.",
                requestId,
                null
        );
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        String requestId = UUID.randomUUID().toString();
        log.error("Unhandled exception [{}]", requestId, ex);
        ErrorResponse response = new ErrorResponse(
                ErrorCode.INTERNAL_ERROR.name(),
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                requestId,
                null
        );
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
