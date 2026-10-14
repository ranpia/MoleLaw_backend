package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
@Profile("!test")
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final DefaultRedisScript<Long> CREATE = script("refresh-create");
    private static final DefaultRedisScript<Long> ROTATE = script("refresh-rotate");
    private static final DefaultRedisScript<Long> REVOKE = script("refresh-revoke");
    private static final DefaultRedisScript<Long> REVOKE_ALL = script("refresh-revoke-all");
    private final StringRedisTemplate redis;

    @Override
    public long generation(Long userId) {
        try {
            // A lost generation key must never revive families from an older generation.
            Boolean initialized = redis.opsForValue().setIfAbsent(prefix(userId) + "generation",
                    Long.toString(nonce()), Duration.ofDays(7));
            if (initialized == null) throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
            String generation = redis.opsForValue().get(prefix(userId) + "generation");
            if (generation == null) throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
            return Long.parseLong(generation);
        } catch (DataAccessException | NumberFormatException e) {
            throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
        }
    }

    @Override
    public void create(Long userId, String familyId, String hash, Instant expiresAt, long generation) {
        long result = execute(CREATE, List.of(prefix(userId) + familyId, prefix(userId) + "generation",
                prefix(userId) + "families"), hash, familyId, Long.toString(expiresAt.toEpochMilli()), Long.toString(generation));
        if (result == -1) throw new MolelawException(ErrorCode.INVALID_REFRESH_TOKEN);
        if (result != 1) throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
    }

    @Override
    public Instant rotate(Long userId, String familyId, String previousHash, String nextHash) {
        long result = execute(ROTATE, List.of(prefix(userId) + familyId, prefix(userId) + "generation"), previousHash, nextHash);
        if (result == -2) throw new MolelawException(ErrorCode.REFRESH_TOKEN_REUSED);
        if (result == -1) throw new MolelawException(ErrorCode.INVALID_REFRESH_TOKEN);
        if (result <= 0) throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
        return Instant.ofEpochMilli(result);
    }

    @Override
    public void revoke(Long userId, String familyId, String knownHash) {
        execute(REVOKE, List.of(prefix(userId) + familyId), knownHash);
    }

    @Override
    public void revokeAll(Long userId) {
        execute(REVOKE_ALL, List.of(prefix(userId) + "generation"), Long.toString(nonce()),
                Long.toString(Duration.ofDays(7).toMillis()));
    }

    private long execute(DefaultRedisScript<Long> script, List<String> keys, String... arguments) {
        try {
            Long result = redis.execute(script, keys, (Object[]) arguments);
            if (result == null) throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
            return result;
        } catch (DataAccessException e) {
            throw new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
        }
    }

    private static String prefix(Long userId) {
        return "molelaw:auth:refresh:v1:{" + userId + "}:";
    }

    private static long nonce() {
        return RANDOM.nextLong(1, Long.MAX_VALUE / 2);
    }

    private static DefaultRedisScript<Long> script(String name) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("auth/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
