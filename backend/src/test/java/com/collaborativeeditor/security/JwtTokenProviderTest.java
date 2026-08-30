package com.collaborativeeditor.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    @Test
    @DisplayName("blank JWT secrets are rejected to avoid insecure startup defaults")
    void blankSecretRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider("", 900000L));
    }
}
