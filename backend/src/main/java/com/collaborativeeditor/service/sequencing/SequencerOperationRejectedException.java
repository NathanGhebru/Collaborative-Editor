package com.collaborativeeditor.service.sequencing;

/**
 * Exception thrown when an operation is rejected due to invalid parameters (e.g. INVALID_POSITION, INVALID_LENGTH, INVALID_OPERATION).
 */
public class SequencerOperationRejectedException extends SequencerValidationException {

    public SequencerOperationRejectedException(String rejectionCode, String message) {
        super(rejectionCode, message);
    }

    public SequencerOperationRejectedException(String rejectionCode, String message, Throwable cause) {
        super(rejectionCode, message, cause);
    }
}
