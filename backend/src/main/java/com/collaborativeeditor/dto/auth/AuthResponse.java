package com.collaborativeeditor.dto.auth;

import com.collaborativeeditor.dto.user.UserDto;

public class AuthResponse {

    private UserDto user;
    private String accessToken;
    private long expiresInSeconds;

    public AuthResponse() {
    }

    public AuthResponse(UserDto user, String accessToken, long expiresInSeconds) {
        this.user = user;
        this.accessToken = accessToken;
        this.expiresInSeconds = expiresInSeconds;
    }

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }
}

