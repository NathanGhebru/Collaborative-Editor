package com.collaborativeeditor.ot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Primitive INSERT operation.
 * Inserts text before the character at the specified 0-indexed UTF-16 position.
 */
public record InsertOperation(
    @JsonProperty("position") int position,
    @JsonProperty("text") String text
) implements Operation {

    @JsonCreator
    public InsertOperation(
        @JsonProperty("position") int position,
        @JsonProperty("text") String text
    ) {
        this.position = position;
        this.text = Objects.requireNonNull(text, "text must not be null");
    }

    @Override
    @JsonProperty("kind")
    public OperationKind getKind() {
        return OperationKind.INSERT;
    }
}

