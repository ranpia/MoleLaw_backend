package com.MoleLaw_backend.service.security;

import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.MolelawException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessTokenServiceTest {
    private final JwtUtil jwt = new JwtUtil("test-secret-key-with-at-least-thirty-two-bytes");
    private final AccessTokenStore store = mock(AccessTokenStore.class);
    private final AccessTokenService service = new AccessTokenService(jwt, store);

    @BeforeEach
    void setUp() {
        jwt.init();
    }

    @Test
    void twoImmediateIssuesHaveDistinctIdsAndAreRegisteredBeforeReturning() {
        String first = service.issue("u@example.com", "local");
        String second = service.issue("u@example.com", "local");
        assertThat(first).isNotEqualTo(second);
        var identity = jwt.getAccessTokenIdentity(first);
        verify(store).register(identity.tokenId(), identity.subject(), identity.expiresAt());
        verify(store, times(2)).register(anyString(), eq("u@example.com:local"), any());
    }

    @Test
    void registrationFailureNeverReturnsAnUnregisteredJwt() {
        doThrow(new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE))
                .when(store).register(anyString(), anyString(), any());
        assertThatThrownBy(() -> service.issue("u@example.com", "local"))
                .isInstanceOf(MolelawException.class);
    }

    @Test
    void revokeIgnoresMissingMalformedAndRefreshTokens() {
        service.revoke(null);
        service.revoke("malformed");
        service.revoke(jwt.generateRefreshToken("u@example.com", "local"));
        verifyNoInteractions(store);
    }
}
