package com.MoleLaw_backend.config;
import com.MoleLaw_backend.dao.RefreshTokenStore;
import com.MoleLaw_backend.dao.InMemoryRefreshTokenStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
@TestConfiguration
public class TestRefreshTokenStoreConfig {
    @Bean
    RefreshTokenStore refreshTokenStore() {
        return new InMemoryRefreshTokenStore();
    }
}
