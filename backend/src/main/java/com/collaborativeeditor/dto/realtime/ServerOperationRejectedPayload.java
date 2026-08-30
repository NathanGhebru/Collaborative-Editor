package com.collaborativeeditor.dto.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record ServerOperationRejectedPayload(
    @JsonProperty("clientOperationId") UUID clientOperationId,
    @JsonProperty("code") String code,
    @JsonProperty("message") String message
) {}
