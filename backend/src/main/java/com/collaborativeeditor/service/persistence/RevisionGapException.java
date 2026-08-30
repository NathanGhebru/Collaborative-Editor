package com.collaborativeeditor.service.persistence;

import java.util.UUID;

/**
 * Exception thrown when a sequence of canonical operation batches contains a revision gap or discontinuity.
 */
public class RevisionGapException extends RuntimeException {

    private final UUID documentId;
    private final UUID syncEpoch;
    private final long expectedRevision;
    private final long actualRevision;

    public RevisionGapException(
            UUID documentId,
            UUID syncEpoch,
            long expectedRevision,
            long actualRevision) {
        super("Revision gap detected for document " + documentId
            + " (epoch: " + syncEpoch
            + "): expected revision " + expectedRevision
            + " but encountered " + actualRevision);
        this.documentId = documentId;
        this.syncEpoch = syncEpoch;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getSyncEpoch() {
        return syncEpoch;
    }

    public long getExpectedRevision() {
        return expectedRevision;
    }

    public long getActualRevision() {
        return actualRevision;
    }
}

