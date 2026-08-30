package com.collaborativeeditor.service.sequencing;

/**
 * Exception thrown when a submitted operation specifies a baseRevision ahead of the server's current revision.
 */
public class FutureRevisionException extends SequencerValidationException {

    private final long baseRevision;
    private final long currentRevision;

    public FutureRevisionException(long baseRevision, long currentRevision) {
        super("REVISION_AHEAD", "Future revision: operation baseRevision " + baseRevision
            + " exceeds server currentRevision " + currentRevision);
        this.baseRevision = baseRevision;
        this.currentRevision = currentRevision;
    }

    public long getBaseRevision() {
        return baseRevision;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }
}
