package com.collaborativeeditor.ot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Server-generated GROUP operation.
 * Contains a sequence of operations executed sequentially.
 */
public record GroupOperation(
    @JsonProperty("operations") List<Operation> operations
) implements Operation {

    @JsonCreator
    public GroupOperation(
        @JsonProperty("operations") List<Operation> operations
    ) {
        this.operations = operations != null ? List.copyOf(operations) : Collections.emptyList();
    }

    @Override
    @JsonProperty("kind")
    public OperationKind getKind() {
        return OperationKind.GROUP;
    }
}

