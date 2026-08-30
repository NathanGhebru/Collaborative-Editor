package com.collaborativeeditor.dto.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record RealtimeTicketResponse(
    @JsonProperty("ticket") String ticket,
    @JsonProperty("expiresAt") OffsetDateTime expiresAt,
    @JsonProperty("websocketPath") String websocketPath
) {}
