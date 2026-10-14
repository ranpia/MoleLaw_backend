package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisRefreshTokenStoreFailureTest {
    @Test
    @SuppressWarnings("unchecked")
    void everyStoreFailureIs503WithoutInProcessFallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        var store = new RedisRefreshTokenStore(redis);
        assertUnavailable(() -> store.generation(1L));
        assertUnavailable(() -> store.create(1L, "family", "hash", Instant.now().plusSeconds(60), 1L));
        assertUnavailable(() -> store.rotate(1L, "family", "old-hash", "new-hash"));
        assertUnavailable(() -> store.revoke(1L, "family", "hash"));
        assertUnavailable(() -> store.revokeAll(1L));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(MolelawException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTH_STORE_UNAVAILABLE));
    }
}
