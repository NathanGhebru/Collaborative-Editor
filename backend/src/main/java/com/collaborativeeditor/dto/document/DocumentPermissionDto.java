package com.collaborativeeditor.dto.document;

import java.time.OffsetDateTime;

public record DocumentPermissionDto(
        DocumentOwnerDto user,
        String role,
        OffsetDateTime createdAt
) {
}

