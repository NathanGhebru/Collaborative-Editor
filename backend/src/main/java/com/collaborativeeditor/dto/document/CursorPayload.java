package com.collaborativeeditor.dto.document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CursorPayload(
        OffsetDateTime updatedAt,
        UUID id
) {
}

