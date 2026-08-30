package com.collaborativeeditor.ot.model;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Envelope representing an operation with client identity, base revision, and payload.
 */
public record ClientOperation(
    @JsonProperty("clientId") String clientId,
    @JsonProperty("clientOperationId") String clientOperationId,
    @JsonProperty("baseRevision") long baseRevision,
    @JsonProperty("operation") Operation operation
) {

    @JsonCreator
    public ClientOperation(
        @JsonProperty("clientId") String clientId,
        @JsonProperty("clientOperationId") String clientOperationId,
        @JsonProperty("baseRevision") long baseRevision,
        @JsonProperty("operation") Operation operation
    ) {
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.clientOperationId = Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
        this.baseRevision = baseRevision;
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
    }

    public static ClientOperation of(UUID clientId, UUID clientOperationId, long baseRevision, Operation operation) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
        return new ClientOperation(clientId.toString(), clientOperationId.toString(), baseRevision, operation);
    }

    @JsonIgnore
    public OperationKey getOperationKey() {
        return new OperationKey(clientId, clientOperationId);
    }

    public ClientOperation withOperation(Operation newOp) {
        return new ClientOperation(clientId, clientOperationId, baseRevision, newOp);
    }

    public ClientOperation withBaseRevision(long newBaseRevision) {
        return new ClientOperation(clientId, clientOperationId, newBaseRevision, operation);
    }
}

