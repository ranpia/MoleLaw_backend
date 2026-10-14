package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("redis-integration")
class RedisRefreshTokenIntegrationTest {
    private static final GenericContainer<?> DATABASE = new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--save", "", "--appendonly", "no",
                    "--requirepass", "isolated-refresh-test-password", "--maxmemory-policy", "noeviction");
    private static final AtomicLong IDS = new AtomicLong(10000);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisRefreshTokenStore store;
    private static RefreshTokenService service;

    @BeforeAll
    static void start() {
        DATABASE.start();
        var config = new RedisStandaloneConfiguration(DATABASE.getHost(), DATABASE.getMappedPort(6379));
        config.setPassword(RedisPassword.of("isolated-refresh-test-password"));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        store = new RedisRefreshTokenStore(redis);
        service = new RefreshTokenService(store);
    }

    @AfterAll
    static void stop() {
        if (factory != null) factory.destroy();
        DATABASE.stop();
    }

    @Test
    void rotationRetainsAbsoluteExpiryStoresHashesAndRevokesFamilyOnReuse() {
        Long userId = IDS.incrementAndGet();
        var first = service.issue(userId);
        var second = service.rotate(first.value());
        assertThat(first.value().equals(second.value())).isFalse();
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        String key = familyKey(first.value());
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(604790L, 604800L);
        var values = redis.opsForHash().entries(key);
        assertThat(values.values().stream().anyMatch(value -> value.equals(first.value()) || value.equals(second.value()))).isFalse();
        assertThat(values.size()).isEqualTo(6); // current, expires, generation, revoked, two issued hashes
        assertError(() -> service.rotate(first.value()), ErrorCode.REFRESH_TOKEN_REUSED);
        assertError(() -> service.rotate(second.value()), ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void unknownSecretCannotRevokeAKnownFamily() {
        var first = service.issue(IDS.incrementAndGet());
        String fabricated = first.value().substring(0, first.value().lastIndexOf('.') + 1) + "A".repeat(43);
        assertError(() -> service.rotate(fabricated), ErrorCode.INVALID_REFRESH_TOKEN);
        service.revoke(fabricated);
        assertThat(service.rotate(first.value()).userId()).isEqualTo(first.userId());
    }

    @Test
    void concurrentConsumptionSucceedsOnceAndReuseRevokesTheReplacement() throws Exception {
        var first = service.issue(IDS.incrementAndGet());
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var replacement = new AtomicReference<RefreshTokenService.Grant>();
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> {
                start.await();
                try {
                    replacement.set(service.rotate(first.value()));
                    return true;
                } catch (MolelawException error) {
                    assertThat(error.getErrorCode()).isIn(ErrorCode.INVALID_REFRESH_TOKEN, ErrorCode.REFRESH_TOKEN_REUSED);
                    return false;
                }
            }));
            start.countDown();
            int successful = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(1);
            assertError(() -> service.rotate(replacement.get().value()), ErrorCode.INVALID_REFRESH_TOKEN);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logoutAndRotationRaceCannotResurrectAFamily() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            var first = service.issue(IDS.incrementAndGet());
            var executor = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            var replacement = new AtomicReference<RefreshTokenService.Grant>();
            try {
                var rotate = executor.submit(() -> {
                    start.await();
                    try { replacement.set(service.rotate(first.value())); }
                    catch (MolelawException error) { assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN); }
                    return null;
                });
                var revoke = executor.submit(() -> { start.await(); service.revoke(first.value()); return null; });
                start.countDown();
                rotate.get(10, TimeUnit.SECONDS);
                revoke.get(10, TimeUnit.SECONDS);
                assertError(() -> service.rotate(first.value()), ErrorCode.INVALID_REFRESH_TOKEN);
                if (replacement.get() != null) assertError(() -> service.rotate(replacement.get().value()), ErrorCode.INVALID_REFRESH_TOKEN);
            } finally { executor.shutdownNow(); }
        }
    }

    @Test
    void allSessionRevocationRejectsStaleRegistrationButAllowsANewLogin() {
        Long userId = IDS.incrementAndGet();
        var first = service.issue(userId);
        var second = service.issue(userId);
        var other = service.issue(IDS.incrementAndGet());
        long staleGeneration = store.generation(userId);
        service.revokeAll(userId);
        assertError(() -> service.rotate(first.value()), ErrorCode.INVALID_REFRESH_TOKEN);
        assertError(() -> service.rotate(second.value()), ErrorCode.INVALID_REFRESH_TOKEN);
        assertError(() -> store.create(userId, UUID.randomUUID().toString(), "a".repeat(64),
                Instant.now().plusSeconds(60), staleGeneration), ErrorCode.INVALID_REFRESH_TOKEN);
        assertThat(service.rotate(service.issue(userId).value()).userId()).isEqualTo(userId);
        assertThat(service.rotate(other.value()).userId()).isEqualTo(other.userId());
    }

    @Test
    void lostGenerationKeyCannotReviveOldFamiliesAfterANewLogin() {
        Long userId = IDS.incrementAndGet();
        var first = service.issue(userId);
        redis.delete(prefix(userId) + "generation");
        assertError(() -> service.rotate(first.value()), ErrorCode.INVALID_REFRESH_TOKEN);
        var replacement = service.issue(userId);
        assertError(() -> service.rotate(first.value()), ErrorCode.INVALID_REFRESH_TOKEN);
        assertThat(service.rotate(replacement.value()).userId()).isEqualTo(userId);
    }

    @Test
    void missingFamilyIsNotRecreatedByRefresh() {
        var first = service.issue(IDS.incrementAndGet());
        String key = familyKey(first.value());
        redis.delete(key);
        assertError(() -> service.rotate(first.value()), ErrorCode.INVALID_REFRESH_TOKEN);
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void expiryIsNotExtendedByRotationAndExpiredKeysAreRejected() {
        Long id = IDS.incrementAndGet();
        String family = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plusMillis(800).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        store.create(id, family, "a".repeat(64), expiry, store.generation(id));
        assertThat(store.rotate(id, family, "a".repeat(64), "b".repeat(64))).isEqualTo(expiry);
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(redis.hasKey(prefix(id) + family)).isFalse());
        assertError(() -> store.rotate(id, family, "b".repeat(64), "c".repeat(64)), ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void redisCommandFailuresAre503WithoutAuthenticationFallback() {
        var grant = service.issue(IDS.incrementAndGet());
        redis.delete(familyKey(grant.value()));
        redis.opsForValue().set(familyKey(grant.value()), "wrong-type-for-failure-test");
        assertError(() -> service.rotate(grant.value()), ErrorCode.AUTH_STORE_UNAVAILABLE);
    }

    @Test
    void noevictionMemoryPressureRejectsIssuanceAndRotationWithoutConsumingTheOldToken() {
        var grant = service.issue(IDS.incrementAndGet());
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                connection.serverCommands().setConfig("maxmemory", "1");
                return null;
            });
            assertError(() -> service.issue(IDS.incrementAndGet()), ErrorCode.AUTH_STORE_UNAVAILABLE);
            assertError(() -> service.rotate(grant.value()), ErrorCode.AUTH_STORE_UNAVAILABLE);
        } finally {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                connection.serverCommands().setConfig("maxmemory", "0");
                return null;
            });
        }
        assertThat(service.rotate(grant.value()).userId()).isEqualTo(grant.userId());
    }

    @Test
    void unreachableRedisRejectsLoginAndRefreshWith503() {
        var config = new RedisStandaloneConfiguration("127.0.0.1", 1);
        var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300)).shutdownTimeout(Duration.ZERO)
                .clientOptions(io.lettuce.core.ClientOptions.builder().autoReconnect(false)
                        .socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build()).build())
                .build();
        var unavailableFactory = new LettuceConnectionFactory(config, client);
        unavailableFactory.afterPropertiesSet();
        unavailableFactory.start();
        var unavailable = new RefreshTokenService(new RedisRefreshTokenStore(new StringRedisTemplate(unavailableFactory)));
        try {
            var existing = service.issue(IDS.incrementAndGet());
            assertError(() -> unavailable.issue(existing.userId()), ErrorCode.AUTH_STORE_UNAVAILABLE);
            assertError(() -> unavailable.rotate(existing.value()), ErrorCode.AUTH_STORE_UNAVAILABLE);
        } finally { unavailableFactory.destroy(); }
    }

    private static String familyKey(String token) {
        String[] parts = token.split("\\.");
        return prefix(Long.valueOf(parts[1])) + parts[2];
    }

    private static String prefix(Long id) { return "molelaw:auth:refresh:v1:{" + id + "}:"; }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(MolelawException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
