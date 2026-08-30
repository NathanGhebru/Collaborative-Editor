package com.collaborativeeditor.service.persistence;

import com.collaborativeeditor.ot.model.Operation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

/**
 * Persisted canonical operation representation stored within batch JSON.
 */
public record PersistedCanonicalOperation(
    @JsonProperty("revision") long revision,
    @JsonProperty("clientId") UUID clientId,
    @JsonProperty("clientOperationId") UUID clientOperationId,
    @JsonProperty("actorUserId") UUID actorUserId,
    @JsonProperty("operation") Operation operation
) {

    @JsonCreator
    public PersistedCanonicalOperation(
        @JsonProperty("revision") long revision,
        @JsonProperty("clientId") UUID clientId,
        @JsonProperty("clientOperationId") UUID clientOperationId,
        @JsonProperty("actorUserId") UUID actorUserId,
        @JsonProperty("operation") Operation operation
    ) {
        this.revision = revision;
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.clientOperationId = Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
        this.actorUserId = actorUserId;
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
    }
}

