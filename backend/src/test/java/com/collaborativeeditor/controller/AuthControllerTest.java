package com.collaborativeeditor.controller;

import com.collaborativeeditor.dto.auth.AuthResponse;
import com.collaborativeeditor.dto.auth.LoginRequest;
import com.collaborativeeditor.dto.auth.RefreshResponse;
import com.collaborativeeditor.dto.auth.RegisterRequest;
import com.collaborativeeditor.dto.user.UserDto;
import com.collaborativeeditor.exception.ApiException;
import com.collaborativeeditor.exception.ErrorCode;
import com.collaborativeeditor.security.JwtAuthenticationFilter;
import com.collaborativeeditor.security.JwtTokenProvider;
import com.collaborativeeditor.security.RestAuthenticationEntryPoint;
import com.collaborativeeditor.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-for-auth-controller-tests-1234567890")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("POST /api/v1/auth/register returns 201 Created and sets rt_token cookie")
    void register_success() throws Exception {
        RegisterRequest request = new RegisterRequest("nathan", "nathan@example.com", "password123", "Nathan");
        UserDto userDto = new UserDto(UUID.randomUUID(), "nathan", null, "Nathan", OffsetDateTime.now());
        AuthResponse response = new AuthResponse(userDto, "jwtTokenString", 900L);
        AuthService.AuthResult result = new AuthService.AuthResult(response, "rawRefreshTokenString");

        when(authService.register(any(RegisterRequest.class), any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("rt_token=rawRefreshTokenString")))
                .andExpect(jsonPath("$.accessToken").value("jwtTokenString"))
                .andExpect(jsonPath("$.user.username").value("nathan"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register returns 422 for invalid username format")
    void register_invalidUsername() throws Exception {
        RegisterRequest request = new RegisterRequest("invalid user!", "nathan@example.com", "password123", "Nathan");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_USERNAME"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register returns 409 USERNAME_TAKEN when username exists")
    void register_usernameTaken() throws Exception {
        RegisterRequest request = new RegisterRequest("nathan", "nathan@example.com", "password123", "Nathan");
        when(authService.register(any(RegisterRequest.class), any(), any()))
                .thenThrow(new ApiException(ErrorCode.USERNAME_TAKEN));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USERNAME_TAKEN"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login returns 200 OK and sets rt_token cookie")
    void login_success() throws Exception {
        LoginRequest request = new LoginRequest("nathan", "password123");
        UserDto userDto = new UserDto(UUID.randomUUID(), "nathan", null, "Nathan", OffsetDateTime.now());
        AuthResponse response = new AuthResponse(userDto, "jwtTokenString", 900L);
        AuthService.AuthResult result = new AuthService.AuthResult(response, "rawRefreshTokenString");

        when(authService.login(any(LoginRequest.class), any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("rt_token=rawRefreshTokenString")))
                .andExpect(jsonPath("$.accessToken").value("jwtTokenString"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login returns 401 INVALID_CREDENTIALS for invalid credentials")
    void login_invalidCredentials() throws Exception {
        LoginRequest request = new LoginRequest("nathan", "wrongpass");
        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenThrow(new ApiException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh returns 200 OK with new access token and rotated cookie")
    void refresh_success() throws Exception {
        RefreshResponse response = new RefreshResponse("newJwtToken", 900L);
        AuthService.RefreshResult result = new AuthService.RefreshResult(response, "newRawRefreshToken");

        when(authService.refresh(eq("validRefreshToken"), any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("rt_token", "validRefreshToken")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("rt_token=newRawRefreshToken")))
                .andExpect(jsonPath("$.accessToken").value("newJwtToken"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh returns 401 REFRESH_TOKEN_MISSING when cookie is missing")
    void refresh_missingCookie() throws Exception {
        when(authService.refresh(eq(null), any(), any()))
                .thenThrow(new ApiException(ErrorCode.REFRESH_TOKEN_MISSING));

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_MISSING"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout returns 204 No Content and clears rt_token cookie")
    void logout_success() throws Exception {
        doNothing().when(authService).logout("validRefreshToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("rt_token", "validRefreshToken")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns 401 UNAUTHENTICATED when Authorization header is missing")
    void me_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}

