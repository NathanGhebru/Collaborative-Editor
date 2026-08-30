package com.collaborativeeditor.service.sequencing;

import com.collaborativeeditor.ot.model.Operation;

import java.util.Objects;
import java.util.UUID;

/**
 * Command DTO for submitting an operation to the sequencing service.
 */
public record SubmitOperationCommand(
    UUID documentId,
    UUID syncEpoch,
    UUID clientId,
    UUID clientOperationId,
    UUID actorUserId,
    long baseRevision,
    Operation operation,
    int maxRetries
) {
    public SubmitOperationCommand(
            UUID documentId,
            UUID syncEpoch,
            UUID clientId,
            UUID clientOperationId,
            UUID actorUserId,
            long baseRevision,
            Operation operation) {
        this(documentId, syncEpoch, clientId, clientOperationId, actorUserId, baseRevision, operation, 30);
    }

    public SubmitOperationCommand {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(syncEpoch, "syncEpoch must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        if (baseRevision < 0) {
            throw new IllegalArgumentException("baseRevision must be non-negative: " + baseRevision);
        }
    }
}
