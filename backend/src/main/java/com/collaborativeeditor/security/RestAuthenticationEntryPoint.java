package com.collaborativeeditor.security;

import com.collaborativeeditor.dto.error.ErrorResponse;
import com.collaborativeeditor.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        String requestId = UUID.randomUUID().toString();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCode.UNAUTHENTICATED.name(),
                ErrorCode.UNAUTHENTICATED.getDefaultMessage(),
                requestId,
                null
        );

        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}

