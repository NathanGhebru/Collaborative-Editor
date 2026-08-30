package com.collaborativeeditor.service.persistence;

import java.util.UUID;

/**
 * Exception thrown when a conditional database update for document revision fails due to concurrent or stale revision state.
 */
public class StaleRevisionFencingException extends RuntimeException {

    private final UUID documentId;
    private final UUID syncEpoch;
    private final long expectedPreviousRevision;
    private final long attemptedNewRevision;

    public StaleRevisionFencingException(
            UUID documentId,
            UUID syncEpoch,
            long expectedPreviousRevision,
            long attemptedNewRevision) {
        super("Conditional update failed for document " + documentId
            + " (epoch: " + syncEpoch
            + "): expected previous revision " + expectedPreviousRevision
            + " but document state did not match.");
        this.documentId = documentId;
        this.syncEpoch = syncEpoch;
        this.expectedPreviousRevision = expectedPreviousRevision;
        this.attemptedNewRevision = attemptedNewRevision;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getSyncEpoch() {
        return syncEpoch;
    }

    public long getExpectedPreviousRevision() {
        return expectedPreviousRevision;
    }

    public long getAttemptedNewRevision() {
        return attemptedNewRevision;
    }
}

