package com.MoleLaw_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;

/** Provider registrations own the client authentication method; standard converters build the token request. */
@Configuration(proxyBeanMethods = false)
public class OAuth2ClientConfig {
    @Bean
    public RestClientAuthorizationCodeTokenResponseClient customAccessTokenResponseClient() {
        return new RestClientAuthorizationCodeTokenResponseClient();
    }
}
