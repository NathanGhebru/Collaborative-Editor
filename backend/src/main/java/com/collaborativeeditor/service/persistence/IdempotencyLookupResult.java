package com.collaborativeeditor.service.persistence;

import java.util.UUID;

/**
 * Result of checking operation idempotency.
 */
public record IdempotencyLookupResult(
    Status status,
    Long revision,
    UUID batchId,
    PersistedCanonicalOperation canonicalOperation
) {

    public enum Status {
        NOT_FOUND,
        DUPLICATE
    }

    public static IdempotencyLookupResult notFound() {
        return new IdempotencyLookupResult(Status.NOT_FOUND, null, null, null);
    }

    public static IdempotencyLookupResult duplicate(Long revision, UUID batchId, PersistedCanonicalOperation canonicalOperation) {
        return new IdempotencyLookupResult(Status.DUPLICATE, revision, batchId, canonicalOperation);
    }

    public boolean isDuplicate() {
        return status == Status.DUPLICATE;
    }
}

