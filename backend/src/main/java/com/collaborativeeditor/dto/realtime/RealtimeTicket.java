package com.collaborativeeditor.dto.realtime;

import com.collaborativeeditor.domain.document.DocumentRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RealtimeTicket(
    String ticket,
    UUID documentId,
    UUID userId,
    DocumentRole role,
    OffsetDateTime expiresAt
) {
    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }
}
