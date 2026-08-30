package com.collaborativeeditor.ot.validation;

/**
 * Exception thrown when an operation fails structural or boundary validation against document state.
 */
public class OperationValidationException extends RuntimeException {

    private final String errorCode;

    public OperationValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

