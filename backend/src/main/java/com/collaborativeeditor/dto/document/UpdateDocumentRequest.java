package com.collaborativeeditor.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateDocumentRequest {

    @NotBlank(message = "Title cannot be blank")
    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    @Pattern(regexp = "^[^\\r\\n]{1,255}$", message = "Title cannot contain line breaks")
    private String title;

    public UpdateDocumentRequest() {
    }

    public UpdateDocumentRequest(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}

