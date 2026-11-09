package com.MoleLaw_backend.config;

import com.MoleLaw_backend.controller.GlobalExceptionHandler;
import org.springframework.web.filter.CorsFilter;

import com.MoleLaw_backend.service.oauth.CustomOAuth2UserService;
import com.MoleLaw_backend.config.security.OAuth2SuccessHandler;
import com.MoleLaw_backend.config.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.MoleLaw_backend.config.security.AllowedOriginFilter;
import com.MoleLaw_backend.config.security.ApiCorsProcessor;
import com.MoleLaw_backend.config.security.SwaggerAccessPolicy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final GlobalExceptionHandler errors;
    private final ApiCorsProcessor corsProcessor;
    private final SwaggerAccessPolicy swaggerAccessPolicy;

    @Value("${security.allowed-origins:${frontend.uri}}")
    private String frontendUri;

    @Bean
    public static org.springframework.security.web.context.SecurityContextRepository jwtRequestSecurityContextRepository() {
        return new org.springframework.security.web.context.RequestAttributeSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            org.springframework.security.web.context.SecurityContextRepository jwtRequestSecurityContextRepository,
            @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource,
            CookieCsrfTokenRepository csrfTokenRepository,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> customAccessTokenResponseClient) throws Exception {
        var corsFilter = new CorsFilter(corsSource);
        corsFilter.setCorsProcessor(corsProcessor);
        http
                .cors(cors -> cors.disable())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(jwtRequestSecurityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs", "/swagger-ui.html")
                        .access((authentication, context) -> swaggerAccessPolicy.authorize())
                        .requestMatchers(
                                "/api/auth/signup", "/api/auth/login", "/api/auth/logout",
                                "/api/auth/csrf", "/api/auth/refresh",
                                "/login/**", "/oauth2/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(errors::commence)
                        .tokenEndpoint(token -> token.accessTokenResponseClient(customAccessTokenResponseClient))
                )
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                )
                .addFilterBefore(new AllowedOriginFilter(allowedOrigins(), errors), CsrfFilter.class)
                .addFilterAt(corsFilter, CorsFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    private List<String> allowedOrigins() {
        return AllowedOriginFilter.parseOrigins(frontendUri);
    }
}
