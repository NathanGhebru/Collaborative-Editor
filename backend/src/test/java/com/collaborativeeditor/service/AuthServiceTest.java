package com.collaborativeeditor.service;

import com.collaborativeeditor.domain.auth.RefreshToken;
import com.collaborativeeditor.domain.auth.RefreshTokenRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.dto.auth.LoginRequest;
import com.collaborativeeditor.dto.auth.RegisterRequest;
import com.collaborativeeditor.exception.ApiException;
import com.collaborativeeditor.exception.ErrorCode;
import com.collaborativeeditor.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String TEST_CLIENT_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "TestUserAgent/1.0";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider);
    }

    @Test
    @DisplayName("register creates user, hashes password, saves refresh token, and returns AuthResult")
    void register_success() {
        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "password123", "Test User");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashedPassword");
        when(jwtTokenProvider.generateToken(any(User.class))).thenReturn("mockJwtToken");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(900L);

        AuthService.AuthResult result = authService.register(request, TEST_USER_AGENT, TEST_CLIENT_IP);

        assertNotNull(result);
        assertEquals("mockJwtToken", result.getResponse().getAccessToken());
        assertEquals(900L, result.getResponse().getExpiresInSeconds());
        assertNotNull(result.getRawRefreshToken());
        assertEquals("testuser", result.getResponse().getUser().getUsername());

        verify(userRepository, times(1)).save(any(User.class));
        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("register throws USERNAME_TAKEN when username exists")
    void register_duplicateUsername() {
        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "password123", "Test User");
        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class, () -> authService.register(request, TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.USERNAME_TAKEN, exception.getErrorCode());

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register throws EMAIL_TAKEN when email exists")
    void register_duplicateEmail() {
        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "password123", "Test User");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class, () -> authService.register(request, TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.EMAIL_TAKEN, exception.getErrorCode());

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("login succeeds with valid credentials")
    void login_success() {
        User user = new User(UUID.randomUUID(), "testuser", "test@example.com", "hashedPassword", "Test User", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        when(userRepository.findByIdentifier("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashedPassword")).thenReturn(true);
        when(jwtTokenProvider.generateToken(user)).thenReturn("mockJwtToken");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(900L);

        LoginRequest request = new LoginRequest("testuser", "password123");
        AuthService.AuthResult result = authService.login(request, TEST_USER_AGENT, TEST_CLIENT_IP);

        assertNotNull(result);
        assertEquals("mockJwtToken", result.getResponse().getAccessToken());
        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("login throws INVALID_CREDENTIALS when user not found")
    void login_userNotFound() {
        when(userRepository.findByIdentifier("unknown")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("unknown", "password123");
        ApiException exception = assertThrows(ApiException.class, () -> authService.login(request, TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    @DisplayName("login throws INVALID_CREDENTIALS when password does not match")
    void login_invalidPassword() {
        User user = new User(UUID.randomUUID(), "testuser", "test@example.com", "hashedPassword", "Test User", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        when(userRepository.findByIdentifier("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpass", "hashedPassword")).thenReturn(false);

        LoginRequest request = new LoginRequest("testuser", "wrongpass");
        ApiException exception = assertThrows(ApiException.class, () -> authService.login(request, TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    @DisplayName("login throws ACCOUNT_DISABLED when account status is disabled")
    void login_accountDisabled() {
        User user = new User(UUID.randomUUID(), "disableduser", "disabled@example.com", "hashedPassword", "Disabled User", AccountStatus.DISABLED, OffsetDateTime.now(), OffsetDateTime.now());
        when(userRepository.findByIdentifier("disableduser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashedPassword")).thenReturn(true);

        LoginRequest request = new LoginRequest("disableduser", "password123");
        ApiException exception = assertThrows(ApiException.class, () -> authService.login(request, TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.ACCOUNT_DISABLED, exception.getErrorCode());
    }

    @Test
    @DisplayName("refresh token rotation invalidates old token and issues new refresh token")
    void refresh_tokenRotation() {
        String rawToken = "sampleRawRefreshToken";
        String tokenHash = authService.hashToken(rawToken);

        User user = new User(UUID.randomUUID(), "testuser", "test@example.com", "hash", "Test User", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        RefreshToken oldToken = new RefreshToken(UUID.randomUUID(), user, tokenHash, OffsetDateTime.now().plusDays(1), null, null, OffsetDateTime.now(), null, TEST_USER_AGENT, TEST_CLIENT_IP);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(oldToken));
        when(jwtTokenProvider.generateToken(user)).thenReturn("newJwtToken");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(900L);

        AuthService.RefreshResult result = authService.refresh(rawToken, TEST_USER_AGENT, TEST_CLIENT_IP);

        assertNotNull(result);
        assertEquals("newJwtToken", result.getResponse().getAccessToken());
        assertNotNull(result.getNewRawRefreshToken());
        assertNotEquals(rawToken, result.getNewRawRefreshToken());

        // Verify old token was revoked
        assertNotNull(oldToken.getRevokedAt());
        assertNotNull(oldToken.getReplacedById());
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("refresh throws REFRESH_TOKEN_MISSING when raw token is null")
    void refresh_missingToken() {
        ApiException exception = assertThrows(ApiException.class, () -> authService.refresh(null, TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.REFRESH_TOKEN_MISSING, exception.getErrorCode());
    }

    @Test
    @DisplayName("refresh throws REFRESH_TOKEN_INVALID when token hash not found in DB")
    void refresh_tokenNotFound() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class, () -> authService.refresh("invalidToken", TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    @DisplayName("refresh throws REFRESH_TOKEN_REVOKED when token has already been revoked")
    void refresh_revokedToken() {
        String rawToken = "revokedRawToken";
        String tokenHash = authService.hashToken(rawToken);
        User user = new User(UUID.randomUUID(), "testuser", "test@example.com", "hash", "Test User", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        RefreshToken revokedToken = new RefreshToken(UUID.randomUUID(), user, tokenHash, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().minusHours(1), UUID.randomUUID(), OffsetDateTime.now(), null, TEST_USER_AGENT, TEST_CLIENT_IP);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(revokedToken));

        ApiException exception = assertThrows(ApiException.class, () -> authService.refresh(rawToken, TEST_USER_AGENT, TEST_CLIENT_IP));
        assertEquals(ErrorCode.REFRESH_TOKEN_REVOKED, exception.getErrorCode());
    }

    @Test
    @DisplayName("logout revokes active refresh token")
    void logout_success() {
        String rawToken = "activeRawToken";
        String tokenHash = authService.hashToken(rawToken);
        User user = new User(UUID.randomUUID(), "testuser", "test@example.com", "hash", "Test User", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        RefreshToken activeToken = new RefreshToken(UUID.randomUUID(), user, tokenHash, OffsetDateTime.now().plusDays(1), null, null, OffsetDateTime.now(), null, TEST_USER_AGENT, TEST_CLIENT_IP);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(activeToken));

        authService.logout(rawToken);

        assertNotNull(activeToken.getRevokedAt());
        verify(refreshTokenRepository, times(1)).save(activeToken);
    }
}
