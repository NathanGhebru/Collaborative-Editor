package com.collaborativeeditor.dto.realtime;

import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ServerOperationsPayload(
    @JsonProperty("operations") List<PersistedCanonicalOperation> operations
) {}
