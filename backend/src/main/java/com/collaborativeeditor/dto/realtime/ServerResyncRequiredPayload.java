package com.collaborativeeditor.dto.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ServerResyncRequiredPayload(
    @JsonProperty("reason") String reason
) {}
