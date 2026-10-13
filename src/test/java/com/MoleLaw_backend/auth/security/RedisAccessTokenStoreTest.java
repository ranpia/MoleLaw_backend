package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisAccessTokenStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisAccessTokenStore store = new RedisAccessTokenStore(redis);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void registersWithRemainingJwtLifetimeAndNeverStoresTheToken() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        store.register("unique-id", "u@example.com:local", Instant.now().plusSeconds(60));
        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).setIfAbsent(eq("molelaw:auth:access:unique-id"), eq("u@example.com:local"), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(58), Duration.ofSeconds(60));
    }

    @Test
    void missingOrMismatchedSubjectFailsClosedWithoutWriting() {
        assertThat(store.isActive("id", "u@example.com:local")).isFalse();
        when(values.get("molelaw:auth:access:id")).thenReturn("other@example.com:local");
        assertThat(store.isActive("id", "u@example.com:local")).isFalse();
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void readWriteAndDeleteFailuresReportStorageError() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("unavailable"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("unavailable"));
        assertUnavailable(() -> store.isActive("id", "subject"));
        assertUnavailable(() -> store.register("id", "subject", Instant.now().plusSeconds(60)));
        assertUnavailable(() -> store.revoke("id"));
    }

    @Test
    void refusesExpiredAndDuplicateRegistrations() {
        assertThatThrownBy(() -> store.register("id", "subject", Instant.now().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertUnavailable(() -> store.register("id", "subject", Instant.now().plusSeconds(60)));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(MolelawException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTH_STORE_UNAVAILABLE));
    }
}
