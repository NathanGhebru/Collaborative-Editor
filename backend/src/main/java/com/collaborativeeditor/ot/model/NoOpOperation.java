package com.collaborativeeditor.ot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NO_OP operation.
 * Represents an operation whose effect was eliminated during transformation.
 */
public final class NoOpOperation implements Operation {

    public static final NoOpOperation INSTANCE = new NoOpOperation();

    @JsonCreator
    public NoOpOperation() {
    }

    @Override
    @JsonProperty("kind")
    public OperationKind getKind() {
        return OperationKind.NO_OP;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof NoOpOperation;
    }

    @Override
    public int hashCode() {
        return OperationKind.NO_OP.hashCode();
    }

    @Override
    public String toString() {
        return "NoOpOperation{}";
    }
}

