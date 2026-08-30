package com.collaborativeeditor.dto.realtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientHelloPayload(
    @JsonProperty("knownEpoch") UUID knownEpoch,
    @JsonProperty("knownRevision") Long knownRevision
) {}
