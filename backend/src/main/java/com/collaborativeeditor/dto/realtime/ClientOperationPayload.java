package com.collaborativeeditor.dto.realtime;

import com.collaborativeeditor.ot.model.Operation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientOperationPayload(
    @JsonProperty("clientOperationId") UUID clientOperationId,
    @JsonProperty("baseRevision") Long baseRevision,
    @JsonProperty("operation") Operation operation
) {}
