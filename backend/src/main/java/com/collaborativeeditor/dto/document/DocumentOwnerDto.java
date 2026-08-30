package com.collaborativeeditor.dto.document;

import com.collaborativeeditor.domain.user.User;

import java.util.UUID;

public record DocumentOwnerDto(
        UUID id,
        String username,
        String displayName
) {
    public static DocumentOwnerDto fromUser(User user) {
        return new DocumentOwnerDto(user.getId(), user.getUsername(), user.getDisplayName());
    }
}

