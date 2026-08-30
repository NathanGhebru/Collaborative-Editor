package com.collaborativeeditor.service.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Model representing a contiguous batch of canonical operations to persist.
 */
public record CanonicalOperationBatch(
    UUID documentId,
    UUID syncEpoch,
    long expectedPreviousRevision,
    List<PersistedCanonicalOperation> operations,
    String contentHashAfter
) {

    public CanonicalOperationBatch {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(syncEpoch, "syncEpoch must not be null");
        Objects.requireNonNull(operations, "operations must not be null");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations batch must not be empty");
        }
    }

    public long getFirstRevision() {
        return operations.get(0).revision();
    }

    public long getLastRevision() {
        return operations.get(operations.size() - 1).revision();
    }

    public int getOperationCount() {
        return operations.size();
    }
}

