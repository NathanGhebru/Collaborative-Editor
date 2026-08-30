package com.collaborativeeditor.service.sequencing;

import java.util.UUID;

/**
 * Exception thrown when a submitted operation specifies a syncEpoch that does not match the server's document epoch.
 */
public class EpochMismatchException extends SequencerValidationException {

    private final UUID expectedEpoch;
    private final UUID actualEpoch;

    public EpochMismatchException(UUID expectedEpoch, UUID actualEpoch) {
        super("EPOCH_MISMATCH", "Epoch mismatch: document is at epoch " + expectedEpoch + " but operation specified " + actualEpoch);
        this.expectedEpoch = expectedEpoch;
        this.actualEpoch = actualEpoch;
    }

    public UUID getExpectedEpoch() {
        return expectedEpoch;
    }

    public UUID getActualEpoch() {
        return actualEpoch;
    }
}
