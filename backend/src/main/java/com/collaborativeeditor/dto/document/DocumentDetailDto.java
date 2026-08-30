package com.collaborativeeditor.dto.document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentDetailDto(
        UUID id,
        String title,
        String content,
        DocumentOwnerDto owner,
        String permission,
        Long currentRevision,
        UUID syncEpoch,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

