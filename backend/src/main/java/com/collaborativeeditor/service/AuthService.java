package com.collaborativeeditor.service;

import com.collaborativeeditor.domain.auth.RefreshToken;
import com.collaborativeeditor.domain.auth.RefreshTokenRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.dto.auth.AuthResponse;
import com.collaborativeeditor.dto.auth.LoginRequest;
import com.collaborativeeditor.dto.auth.RefreshResponse;
import com.collaborativeeditor.dto.auth.RegisterRequest;
import com.collaborativeeditor.dto.user.UserDto;
import com.collaborativeeditor.exception.ApiException;
import com.collaborativeeditor.exception.ErrorCode;
import com.collaborativeeditor.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT.AUTH");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public static class AuthResult {
        private final AuthResponse response;
        private final String rawRefreshToken;

        public AuthResult(AuthResponse response, String rawRefreshToken) {
            this.response = response;
            this.rawRefreshToken = rawRefreshToken;
        }

        public AuthResponse getResponse() {
            return response;
        }

        public String getRawRefreshToken() {
            return rawRefreshToken;
        }
    }

    public static class RefreshResult {
        private final RefreshResponse response;
        private final String newRawRefreshToken;

        public RefreshResult(RefreshResponse response, String newRawRefreshToken) {
            this.response = response;
            this.newRawRefreshToken = newRawRefreshToken;
        }

        public RefreshResponse getResponse() {
            return response;
        }

        public String getNewRawRefreshToken() {
            return newRawRefreshToken;
        }
    }

    @Transactional
    public AuthResult register(RegisterRequest request, String userAgent, String ipAddress) {
        String normalizedUsername = request.getUsername().trim().toLowerCase();
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByUsername(normalizedUsername)) {
            auditLog.warn("AUTH_REGISTRATION_FAILED username_taken username={} ip={}", normalizedUsername, ipAddress);
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            auditLog.warn("AUTH_REGISTRATION_FAILED email_taken username={} ip={}", normalizedUsername, ipAddress);
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }

        String passwordHash = passwordEncoder.encode(request.getPassword());
        User user = new User(
                UUID.randomUUID(),
                normalizedUsername,
                normalizedEmail,
                passwordHash,
                request.getDisplayName().trim(),
                AccountStatus.ACTIVE,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        userRepository.save(user);

        String rawRefreshToken = generateSecureRandomToken();
        String tokenHash = hashToken(rawRefreshToken);

        RefreshToken refreshToken = new RefreshToken(
                UUID.randomUUID(),
                user,
                tokenHash,
                OffsetDateTime.now().plusSeconds(refreshExpirationMs / 1000),
                null,
                null,
                OffsetDateTime.now(),
                null,
                userAgent,
                ipAddress
        );
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtTokenProvider.generateToken(user);
        UserDto userDto = UserDto.fromEntity(user, false);
        AuthResponse response = new AuthResponse(userDto, accessToken, jwtTokenProvider.getExpirationSeconds());

        auditLog.info("AUTH_REGISTRATION_SUCCESS userId={} username={} ip={}", user.getId(), user.getUsername(), ipAddress);
        return new AuthResult(response, rawRefreshToken);
    }

    @Transactional
    public AuthResult login(LoginRequest request, String userAgent, String ipAddress) {
        String identifier = request.getIdentifier().trim().toLowerCase();
        Optional<User> userOpt = userRepository.findByIdentifier(identifier);

        if (userOpt.isEmpty()) {
            auditLog.warn("AUTH_LOGIN_FAILED invalid_user identifier={} ip={}", identifier, ipAddress);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditLog.warn("AUTH_LOGIN_FAILED invalid_password userId={} ip={}", user.getId(), ipAddress);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.getAccountStatus() == AccountStatus.DISABLED) {
            auditLog.warn("AUTH_ACCOUNT_DISABLED userId={} ip={}", user.getId(), ipAddress);
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }

        String rawRefreshToken = generateSecureRandomToken();
        String tokenHash = hashToken(rawRefreshToken);

        RefreshToken refreshToken = new RefreshToken(
                UUID.randomUUID(),
                user,
                tokenHash,
                OffsetDateTime.now().plusSeconds(refreshExpirationMs / 1000),
                null,
                null,
                OffsetDateTime.now(),
                null,
                userAgent,
                ipAddress
        );
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtTokenProvider.generateToken(user);
        UserDto userDto = UserDto.fromEntity(user, false);
        AuthResponse response = new AuthResponse(userDto, accessToken, jwtTokenProvider.getExpirationSeconds());

        auditLog.info("AUTH_LOGIN_SUCCESS userId={} username={} ip={}", user.getId(), user.getUsername(), ipAddress);
        return new AuthResult(response, rawRefreshToken);
    }

    @Transactional
    public RefreshResult refresh(String rawRefreshToken, String userAgent, String ipAddress) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            auditLog.warn("AUTH_REFRESH_FAILED missing_cookie ip={}", ipAddress);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_MISSING);
        }

        String tokenHash = hashToken(rawRefreshToken);
        Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            auditLog.warn("AUTH_REFRESH_FAILED invalid_token ip={}", ipAddress);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        RefreshToken oldToken = tokenOpt.get();
        if (oldToken.isRevoked()) {
            auditLog.warn("AUTH_REFRESH_FAILED revoked_token tokenId={} userId={} ip={}", oldToken.getId(), oldToken.getUser().getId(), ipAddress);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        if (oldToken.isExpired()) {
            auditLog.warn("AUTH_REFRESH_FAILED expired_token tokenId={} userId={} ip={}", oldToken.getId(), oldToken.getUser().getId(), ipAddress);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        User user = oldToken.getUser();
        if (user.getAccountStatus() == AccountStatus.DISABLED) {
            auditLog.warn("AUTH_REFRESH_FAILED account_disabled userId={} ip={}", user.getId(), ipAddress);
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }

        // Token rotation
        String newRawRefreshToken = generateSecureRandomToken();
        String newTokenHash = hashToken(newRawRefreshToken);

        RefreshToken newToken = new RefreshToken(
                UUID.randomUUID(),
                user,
                newTokenHash,
                OffsetDateTime.now().plusSeconds(refreshExpirationMs / 1000),
                null,
                null,
                OffsetDateTime.now(),
                null,
                userAgent,
                ipAddress
        );
        refreshTokenRepository.save(newToken);

        oldToken.setRevokedAt(OffsetDateTime.now());
        oldToken.setReplacedById(newToken.getId());
        oldToken.setLastUsedAt(OffsetDateTime.now());
        refreshTokenRepository.save(oldToken);

        String accessToken = jwtTokenProvider.generateToken(user);
        RefreshResponse response = new RefreshResponse(accessToken, jwtTokenProvider.getExpirationSeconds());

        auditLog.info("AUTH_REFRESH_SUCCESS userId={} oldTokenId={} newTokenId={} ip={}", user.getId(), oldToken.getId(), newToken.getId(), ipAddress);
        return new RefreshResult(response, newRawRefreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = hashToken(rawRefreshToken);
            Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);
            if (tokenOpt.isPresent()) {
                RefreshToken token = tokenOpt.get();
                if (!token.isRevoked()) {
                    token.setRevokedAt(OffsetDateTime.now());
                    token.setLastUsedAt(OffsetDateTime.now());
                    refreshTokenRepository.save(token);
                    auditLog.info("AUTH_LOGOUT_SUCCESS tokenId={} userId={}", token.getId(), token.getUser().getId());
                    return;
                }
            }
        }
        auditLog.info("AUTH_LOGOUT_NOOP");
    }

    private String generateSecureRandomToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
