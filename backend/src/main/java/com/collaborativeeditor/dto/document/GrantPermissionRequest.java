package com.collaborativeeditor.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class GrantPermissionRequest {

    @NotBlank(message = "userIdentifier is required")
    private String userIdentifier;

    @NotBlank(message = "role is required")
    @Pattern(regexp = "^EDITOR$", message = "role must be EDITOR")
    private String role;

    public GrantPermissionRequest() {
    }

    public GrantPermissionRequest(String userIdentifier, String role) {
        this.userIdentifier = userIdentifier;
        this.role = role;
    }

    public String getUserIdentifier() {
        return userIdentifier;
    }

    public void setUserIdentifier(String userIdentifier) {
        this.userIdentifier = userIdentifier;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}

