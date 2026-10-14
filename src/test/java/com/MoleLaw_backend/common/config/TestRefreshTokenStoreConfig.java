package com.MoleLaw_backend.common.config;
import com.MoleLaw_backend.auth.security.RefreshTokenStore;
import com.MoleLaw_backend.auth.security.InMemoryRefreshTokenStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
@TestConfiguration
public class TestRefreshTokenStoreConfig {
    @Bean
    RefreshTokenStore refreshTokenStore() {
        return new InMemoryRefreshTokenStore();
    }
}
