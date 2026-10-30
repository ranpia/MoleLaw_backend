package com.MoleLaw_backend.auth.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AndRequestMatcher;

/** Read-only metrics on the unpublished management port in the isolated test network. */
@Configuration(proxyBeanMethods = false)
@Profile("loadtest")
public class LoadTestManagementSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain loadTestManagementSecurityFilterChain(HttpSecurity http, Environment environment)
            throws Exception {
        http.securityMatcher(new AndRequestMatcher(
                        EndpointRequest.toAnyEndpoint(),
                        request -> request.getLocalPort() == environment.getProperty(
                                "local.management.port", Integer.class,
                                environment.getProperty("management.server.port", Integer.class, -1))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
