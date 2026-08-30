package com.collaborativeeditor.service.sequencing;

/**
 * Base exception for validation errors during operation sequencing.
 */
public class SequencerValidationException extends RuntimeException {

    private final String rejectionCode;

    public SequencerValidationException(String rejectionCode, String message) {
        super(message);
        this.rejectionCode = rejectionCode;
    }

    public SequencerValidationException(String rejectionCode, String message, Throwable cause) {
        super(message, cause);
        this.rejectionCode = rejectionCode;
    }

    public String getRejectionCode() {
        return rejectionCode;
    }
}
