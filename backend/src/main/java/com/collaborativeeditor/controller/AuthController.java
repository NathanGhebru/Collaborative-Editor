package com.collaborativeeditor.controller;

import com.collaborativeeditor.dto.auth.*;
import com.collaborativeeditor.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        String ipAddress = httpRequest.getRemoteAddr();

        AuthService.AuthResult result = authService.register(request, userAgent, ipAddress);
        setRefreshCookie(httpResponse, result.getRawRefreshToken(), Duration.ofDays(7));

        return ResponseEntity.status(HttpStatus.CREATED).body(result.getResponse());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        String ipAddress = httpRequest.getRemoteAddr();

        AuthService.AuthResult result = authService.login(request, userAgent, ipAddress);
        setRefreshCookie(httpResponse, result.getRawRefreshToken(), Duration.ofDays(7));

        return ResponseEntity.ok(result.getResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(
            @CookieValue(name = "rt_token", required = false) String refreshTokenCookie,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        String ipAddress = httpRequest.getRemoteAddr();

        AuthService.RefreshResult result = authService.refresh(refreshTokenCookie, userAgent, ipAddress);
        setRefreshCookie(httpResponse, result.getNewRawRefreshToken(), Duration.ofDays(7));

        return ResponseEntity.ok(result.getResponse());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "rt_token", required = false) String refreshTokenCookie,
            HttpServletResponse httpResponse) {

        authService.logout(refreshTokenCookie);
        clearRefreshCookie(httpResponse);

        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from("rt_token", refreshToken)
                .httpOnly(true)
                .secure(false) // local dev mode compatibility; set true in prod via HTTPS
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("rt_token", "")
                .httpOnly(true)
                .secure(false)
                .path("/api/v1/auth")
                .maxAge(0)
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

