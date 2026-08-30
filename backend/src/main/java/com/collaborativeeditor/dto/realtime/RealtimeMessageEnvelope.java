package com.collaborativeeditor.dto.realtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeMessageEnvelope(
    @JsonProperty("protocolVersion") Integer protocolVersion,
    @JsonProperty("type") String type,
    @JsonProperty("messageId") UUID messageId,
    @JsonProperty("documentId") UUID documentId,
    @JsonProperty("syncEpoch") UUID syncEpoch,
    @JsonProperty("clientId") UUID clientId,
    @JsonProperty("timestamp") OffsetDateTime timestamp,
    @JsonProperty("payload") JsonNode payload
) {}
