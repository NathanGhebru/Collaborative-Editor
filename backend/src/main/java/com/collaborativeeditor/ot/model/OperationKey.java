package com.collaborativeeditor.ot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * OperationKey identifies an operation's author and local id for deterministic tie-breaking.
 * Tie-breaking uses lexicographical comparison of lowercase canonical UUID strings.
 */
public record OperationKey(
    @JsonProperty("clientId") String clientId,
    @JsonProperty("clientOperationId") String clientOperationId
) implements Comparable<OperationKey> {

    @JsonCreator
    public OperationKey(
        @JsonProperty("clientId") String clientId,
        @JsonProperty("clientOperationId") String clientOperationId
    ) {
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null").toLowerCase(Locale.ROOT);
        this.clientOperationId = Objects.requireNonNull(clientOperationId, "clientOperationId must not be null").toLowerCase(Locale.ROOT);
    }

    public static OperationKey of(UUID clientId, UUID clientOperationId) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
        return new OperationKey(clientId.toString(), clientOperationId.toString());
    }

    @Override
    public int compareTo(OperationKey other) {
        if (other == null) {
            return -1;
        }
        int clientComparison = this.clientId.compareTo(other.clientId);
        if (clientComparison != 0) {
            return clientComparison;
        }
        return this.clientOperationId.compareTo(other.clientOperationId);
    }
}

