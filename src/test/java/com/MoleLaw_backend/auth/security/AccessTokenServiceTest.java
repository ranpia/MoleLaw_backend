package com.MoleLaw_backend.auth.security;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenServiceTest {
    @Test
    void issuesDistinctValidTokensWithoutAnAuthenticationStore() {
        JwtUtil jwt = new JwtUtil("test-secret-key-with-at-least-thirty-two-bytes", Duration.ofMinutes(5));
        jwt.init();
        AccessTokenService service = new AccessTokenService(jwt);
        String first = service.issue(1L);
        String second = service.issue(1L);
        assertThat(first).isNotEqualTo(second);
        assertThat(jwt.validateAccessToken(first)).isTrue();
        assertThat(jwt.getUserIdFromAccessToken(first)).isEqualTo(1L);
    }
}