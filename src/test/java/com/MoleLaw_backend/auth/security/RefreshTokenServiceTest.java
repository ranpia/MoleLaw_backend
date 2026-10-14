package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {
    @Test
    void issuesHighEntropyOpaqueTokenAndStoresOnlyItsHashWithAbsoluteExpiry() {
        RefreshTokenStore store = mock(RefreshTokenStore.class);
        when(store.generation(42L)).thenReturn(7L);
        RefreshTokenService service = new RefreshTokenService(store);
        Instant before = Instant.now();
        var grant = service.issue(42L);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(store).create(eq(42L), anyString(), hash.capture(), eq(grant.expiresAt()), eq(7L));
        assertThat(hash.getValue()).matches("[0-9a-f]{64}");
        assertThat(hash.getValue().equals(grant.value())).isFalse();
        assertThat(grant.value().split("\\.")[3].length()).isEqualTo(43);
        assertThat(service.userId(grant.value())).isEqualTo(42L);
        assertThat(Duration.between(before, grant.expiresAt())).isBetween(Duration.ofDays(7), Duration.ofDays(7).plusSeconds(1));
        assertThat(grant.toString().contains(grant.value())).isFalse();
    }

    @Test
    void rejectsMalformedLegacyAndOutOfRangeTokensWithoutStoreCalls() {
        RefreshTokenStore store = mock(RefreshTokenStore.class);
        RefreshTokenService service = new RefreshTokenService(store);
        for (String value : new String[]{null, "", "x".repeat(111), TestJwtTokens.legacyRefresh(1L),
                "r1.0.00000000-0000-0000-0000-000000000000." + "A".repeat(43),
                "r1.9223372036854775808.00000000-0000-0000-0000-000000000000." + "A".repeat(43)}) {
            assertThatThrownBy(() -> service.rotate(value)).isInstanceOfSatisfying(MolelawException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
        }
        verifyNoInteractions(store);
    }

    @Test
    void rotationUsesTheStoresOriginalExpiryRatherThanExtendingSevenDays() {
        RefreshTokenStore store = new InMemoryRefreshTokenStore();
        RefreshTokenService service = new RefreshTokenService(store);
        var first = service.issue(42L);
        var second = service.rotate(first.value());
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(second.value().equals(first.value())).isFalse();
    }
}
