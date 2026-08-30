package com.collaborativeeditor.ot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Primitive DELETE operation.
 * Deletes length UTF-16 code units starting at position.
 */
public record DeleteOperation(
    @JsonProperty("position") int position,
    @JsonProperty("length") int length
) implements Operation {

    @JsonCreator
    public DeleteOperation(
        @JsonProperty("position") int position,
        @JsonProperty("length") int length
    ) {
        this.position = position;
        this.length = length;
    }

    @Override
    @JsonProperty("kind")
    public OperationKind getKind() {
        return OperationKind.DELETE;
    }
}

