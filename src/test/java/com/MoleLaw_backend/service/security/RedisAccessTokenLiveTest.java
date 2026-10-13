package com.MoleLaw_backend.service.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("redis-live")
class RedisAccessTokenLiveTest {
    @Test
    void realRedisEnforcesTtlSubjectAndRevocationWithoutStoringJwt() throws Exception {
        Properties config = new Properties();
        Path composeEnv = Path.of("docker", ".env.compose");
        if (Files.isRegularFile(composeEnv)) {
            try (var reader = Files.newBufferedReader(composeEnv)) {
                config.load(reader);
            }
        }
        RedisStandaloneConfiguration connection = new RedisStandaloneConfiguration(
                setting(config, "REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(setting(config, "REDIS_PORT", "6379")));
        connection.setPassword(RedisPassword.of(setting(config, "REDIS_PASSWORD", "")));
        connection.setDatabase(Integer.parseInt(setting(config, "REDIS_DATABASE", "0")));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(connection);
        factory.afterPropertiesSet();
        factory.start();
        String id = UUID.randomUUID().toString();
        String key = "molelaw:auth:access:" + id;
        String revokedId = UUID.randomUUID().toString();
        String revokedKey = "molelaw:auth:access:" + revokedId;
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        RedisAccessTokenStore store = new RedisAccessTokenStore(redis);
        try {
            store.register(id, "redis-test@example.com:local", Instant.now().plusSeconds(3));
            assertThat(store.isActive(id, "redis-test@example.com:local")).isTrue();
            assertThat(store.isActive(id, "other@example.com:local")).isFalse();
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 3000L);
            assertThat(redis.opsForValue().get(key)).isEqualTo("redis-test@example.com:local");
            store.register(revokedId, "redis-test@example.com:local", Instant.now().plusSeconds(60));
            store.revoke(revokedId);
            assertThat(store.isActive(revokedId, "redis-test@example.com:local")).isFalse();
            await().atMost(Duration.ofSeconds(6)).untilAsserted(() ->
                    assertThat(store.isActive(id, "redis-test@example.com:local")).isFalse());
            assertThat(redis.hasKey(key)).isFalse();
        } finally {
            redis.delete(java.util.List.of(key, revokedKey));
            factory.destroy();
        }
    }

    private String setting(Properties config, String name, String fallback) {
        String environment = System.getenv(name);
        return environment == null || environment.isBlank() ? config.getProperty(name, fallback) : environment;
    }
}
