package com.collaborativeeditor.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateDocumentRequest {

    @NotBlank(message = "Title cannot be blank")
    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    @Pattern(regexp = "^[^\\r\\n]{1,255}$", message = "Title cannot contain line breaks")
    private String title;

    @Size(max = 1000000, message = "Initial content exceeds maximum allowed size of 1,000,000 characters")
    private String initialContent;

    public CreateDocumentRequest() {
    }

    public CreateDocumentRequest(String title) {
        this.title = title;
    }

    public CreateDocumentRequest(String title, String initialContent) {
        this.title = title;
        this.initialContent = initialContent;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getInitialContent() {
        return initialContent;
    }

    public void setInitialContent(String initialContent) {
        this.initialContent = initialContent;
    }
}

