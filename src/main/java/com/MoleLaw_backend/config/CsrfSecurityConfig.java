package com.MoleLaw_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
public class CsrfSecurityConfig {
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(Environment environment,
            @Value("${cookie.secure:true}") boolean secure,
            @Value("${cookie.same-site:None}") String sameSite,
            @Value("${cookie.csrf-http-only:true}") boolean httpOnly) {
        if (!httpOnly && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalArgumentException("Readable CSRF cookies are restricted to local or test profiles");
        }
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(httpOnly).secure(secure).sameSite(sameSite).path("/"));
        return repository;
    }
}
