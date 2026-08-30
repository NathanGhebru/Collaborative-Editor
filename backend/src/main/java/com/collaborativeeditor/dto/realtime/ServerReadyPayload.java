package com.collaborativeeditor.dto.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record ServerReadyPayload(
    @JsonProperty("connectionId") UUID connectionId,
    @JsonProperty("revision") Long revision,
    @JsonProperty("role") String role
) {}
