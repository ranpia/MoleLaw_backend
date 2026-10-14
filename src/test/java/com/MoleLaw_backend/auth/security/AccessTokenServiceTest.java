package com.MoleLaw_backend.auth.security;

import java.time.Duration;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessTokenServiceTest {
    private final JwtUtil jwt = new JwtUtil("test-secret-key-with-at-least-thirty-two-bytes", Duration.ofMinutes(5));
    private final AccessTokenStore store = mock(AccessTokenStore.class);
    private final AccessTokenService service = new AccessTokenService(jwt, store);

    @BeforeEach
    void setUp() {
        jwt.init();
    }

    @Test
    void twoImmediateIssuesHaveDistinctIdsAndAreRegisteredBeforeReturning() {
        String first = service.issue(1L);
        String second = service.issue(1L);
        assertThat(first.equals(second)).isFalse();
        var identity = jwt.getAccessTokenIdentity(first);
        verify(store).register(identity.tokenId(), identity.subject(), identity.expiresAt());
        verify(store, times(2)).register(anyString(), eq("1"), any());
    }

    @Test
    void registrationFailureNeverReturnsAnUnregisteredJwt() {
        doThrow(new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE))
                .when(store).register(anyString(), anyString(), any());
        assertThatThrownBy(() -> service.issue(1L))
                .isInstanceOf(MolelawException.class);
    }

    @Test
    void revokeIgnoresMissingMalformedAndRefreshTokens() {
        service.revoke(null);
        service.revoke("malformed");
        service.revoke(TestJwtTokens.legacyRefresh(1L));
        verifyNoInteractions(store);
    }
}
