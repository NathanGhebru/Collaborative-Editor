package com.collaborativeeditor.ot.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base sealed interface for all OT document operations.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "kind"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = InsertOperation.class, name = "INSERT"),
    @JsonSubTypes.Type(value = DeleteOperation.class, name = "DELETE"),
    @JsonSubTypes.Type(value = NoOpOperation.class, name = "NO_OP"),
    @JsonSubTypes.Type(value = GroupOperation.class, name = "GROUP")
})
public sealed interface Operation permits InsertOperation, DeleteOperation, NoOpOperation, GroupOperation {

    OperationKind getKind();
}

