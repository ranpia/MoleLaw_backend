package com.MoleLaw_backend.service.security;

import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.MolelawException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

@Repository
@Profile("!test")
@RequiredArgsConstructor
public class RedisAccessTokenStore implements AccessTokenStore {
    private static final String KEY_PREFIX = "molelaw:auth:access:";
    private final StringRedisTemplate redis;

    @Override
    public void register(String tokenId, String subject, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Cannot register an expired Access token");
        }
        try {
            // Registration is exclusive and writes value + expiry in one Redis command.
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(tokenId), subject, ttl))) {
                throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
            }
        } catch (DataAccessException e) {
            throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
        }
    }

    @Override
    public boolean isActive(String tokenId, String subject) {
        try {
            return subject.equals(redis.opsForValue().get(key(tokenId)));
        } catch (DataAccessException e) {
            throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
        }
    }

    @Override
    public void revoke(String tokenId) {
        try {
            redis.delete(key(tokenId));
        } catch (DataAccessException e) {
            throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
        }
    }

    private String key(String tokenId) {
        return KEY_PREFIX + tokenId;
    }
}
