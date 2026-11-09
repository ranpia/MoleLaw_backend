package com.MoleLaw_backend.config;

import com.MoleLaw_backend.config.security.AllowedOriginFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class CorsSecurityConfig {
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${security.allowed-origins:${frontend.uri}}") String allowedOrigins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(AllowedOriginFilter.parseOrigins(allowedOrigins));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-XSRF-TOKEN", "Accept"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
