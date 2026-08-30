package com.collaborativeeditor.service.persistence;

import java.util.UUID;

/**
 * Exception thrown when a client operation ID is reused with a conflicting operation payload.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final UUID documentId;
    private final UUID syncEpoch;
    private final UUID clientId;
    private final UUID clientOperationId;

    public IdempotencyConflictException(
            UUID documentId,
            UUID syncEpoch,
            UUID clientId,
            UUID clientOperationId,
            String message) {
        super("Idempotency conflict for document " + documentId
            + ", clientId " + clientId
            + ", opId " + clientOperationId + ": " + message);
        this.documentId = documentId;
        this.syncEpoch = syncEpoch;
        this.clientId = clientId;
        this.clientOperationId = clientOperationId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getSyncEpoch() {
        return syncEpoch;
    }

    public UUID getClientId() {
        return clientId;
    }

    public UUID getClientOperationId() {
        return clientOperationId;
    }
}

