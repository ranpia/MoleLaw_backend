package com.MoleLaw_backend.config;

import com.MoleLaw_backend.service.oauth.CustomOAuth2UserService;
import com.MoleLaw_backend.service.oauth.OAuth2SuccessHandler;
import com.MoleLaw_backend.service.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.MoleLaw_backend.service.security.AllowedOriginFilter;
import org.springframework.http.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.Collections;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final org.springframework.core.env.Environment environment;

    @Value("${security.allowed-origins:${frontend.uri}}")
    private String frontendUri;
    @Value("${cookie.secure:true}")
    private boolean cookieSecure;
    @Value("${cookie.same-site:None}")
    private String cookieSameSite;
    @Value("${cookie.csrf-http-only:true}")
    private boolean csrfHttpOnly;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs", "/swagger-ui.html")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "test"))
                                        && (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)
                                        || environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false))))
                        .requestMatchers(
                                "/api/auth/signup", "/api/auth/login", "/api/auth/logout",
                                "/api/auth/csrf", "/api/auth/reissue",
                                "/login/**", "/oauth2/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                        .tokenEndpoint(token -> token.accessTokenResponseClient(customAccessTokenResponseClient()))
                )
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\": \"Unauthorized\"}");
                        })
                )
                .addFilterBefore(new AllowedOriginFilter(allowedOrigins()), CsrfFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> customAccessTokenResponseClient() {
        DefaultAuthorizationCodeTokenResponseClient client = new DefaultAuthorizationCodeTokenResponseClient();

        client.setRequestEntityConverter(request -> {
            ClientRegistration registration = request.getClientRegistration();
            String redirectUri = request.getAuthorizationExchange()
                    .getAuthorizationRequest()
                    .getRedirectUri();

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add(OAuth2ParameterNames.GRANT_TYPE, "authorization_code");
            body.add(OAuth2ParameterNames.CLIENT_ID, registration.getClientId());
            body.add(OAuth2ParameterNames.CLIENT_SECRET, registration.getClientSecret());
            body.add(OAuth2ParameterNames.CODE, request.getAuthorizationExchange().getAuthorizationResponse().getCode());
            body.add(OAuth2ParameterNames.REDIRECT_URI, redirectUri);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            URI uri = URI.create(registration.getProviderDetails().getTokenUri());
            return new RequestEntity<>(body, headers, HttpMethod.POST, uri);
        });

        return client;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(allowedOrigins());
        config.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-XSRF-TOKEN", "Accept"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        if (!csrfHttpOnly && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "test"))) {
            throw new IllegalArgumentException("Readable CSRF cookies are restricted to local or test profiles");
        }
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(csrfHttpOnly).secure(cookieSecure)
                .sameSite(cookieSameSite).path("/"));
        return repository;
    }

    private List<String> allowedOrigins() {
        return AllowedOriginFilter.parseOrigins(frontendUri);
    }
}
