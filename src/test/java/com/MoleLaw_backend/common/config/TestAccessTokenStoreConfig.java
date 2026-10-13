package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.auth.security.AccessTokenStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only substitute; production never falls back to an in-process store. */
@TestConfiguration
public class TestAccessTokenStoreConfig {
    @Bean
    AccessTokenStore accessTokenStore() {
        return new AccessTokenStore() {
            private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

            @Override
            public void register(String tokenId, String subject, Instant expiresAt) {
                entries.put(tokenId, new Entry(subject, expiresAt));
            }

            @Override
            public boolean isActive(String tokenId, String subject) {
                Entry entry = entries.get(tokenId);
                return entry != null && entry.subject().equals(subject)
                        && entry.expiresAt().isAfter(Instant.now());
            }

            @Override
            public void revoke(String tokenId) {
                entries.remove(tokenId);
            }
        };
    }

    private record Entry(String subject, Instant expiresAt) {}
}
