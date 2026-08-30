package com.collaborativeeditor.dto.document;

import java.util.List;

public record DocumentListResponse(
        List<DocumentSummaryDto> documents,
        String nextCursor
) {
}

