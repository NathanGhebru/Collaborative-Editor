package com.collaborativeeditor.dto.document;

import java.util.List;

public record DocumentPermissionsListResponse(
        DocumentOwnerDto owner,
        List<DocumentPermissionDto> permissions
) {
}

