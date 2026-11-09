package com.MoleLaw_backend.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OAuth2ClientConfigTest {
    private static final String TOKEN_URI = "https://provider.example/token";

    @ParameterizedTest
    @ValueSource(strings = {"google", "kakao"})
    void standardClientPostsCredentialsAndPreservesPkceAndRedirect(String provider) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OAuth2ClientConfig().customAccessTokenResponseClient();
        client.setRestClient(builder.messageConverters(converters -> {
            converters.clear();
            converters.add(new FormHttpMessageConverter());
            converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
        }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        var expected = new org.springframework.util.LinkedMultiValueMap<String, String>();
        expected.add("grant_type", "authorization_code");
        expected.add("client_id", "synthetic-client");
        expected.add("client_secret", "synthetic-secret");
        expected.add("code", "synthetic-code");
        expected.add("redirect_uri", "https://app.example/callback");
        expected.add("code_verifier", "synthetic-verifier");
        server.expect(requestTo(TOKEN_URI)).andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("{\"access_token\":\"synthetic-access\",\"token_type\":\"Bearer\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        assertThat(client.getTokenResponse(grant(provider)).getAccessToken().getTokenValue()).isEqualTo("synthetic-access");
        server.verify();
    }

    @Test
    void providerErrorRemainsAnOAuthFailure() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OAuth2ClientConfig().customAccessTokenResponseClient();
        client.setRestClient(builder.defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        server.expect(requestTo(TOKEN_URI)).andRespond(withBadRequest()
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));
        assertThatThrownBy(() -> client.getTokenResponse(grant("google")))
                .isInstanceOfSatisfying(OAuth2AuthorizationException.class,
                        failure -> assertThat(failure.getError().getErrorCode()).isEqualTo("invalid_grant"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "kakao"})
    void providerConfigurationUsesTheStandardPostAuthenticationName(String provider) throws Exception {
        String resource = "kakao".equals(provider) ? "application-kakao.yml" : "application.yml";
        var sources = new YamlPropertySourceLoader().load(provider, new ClassPathResource(resource));
        assertThat(sources.get(0).getProperty("spring.security.oauth2.client.registration."
                + provider + ".client-authentication-method")).isEqualTo("client_secret_post");
    }

    private OAuth2AuthorizationCodeGrantRequest grant(String provider) {
        var registration = ClientRegistration.withRegistrationId(provider).clientId("synthetic-client")
                .clientSecret("synthetic-secret").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example/callback").authorizationUri("https://provider.example/authorize")
                .tokenUri(TOKEN_URI).build();
        var authorization = OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://provider.example/authorize")
                .clientId("synthetic-client").redirectUri("https://app.example/callback").state("synthetic-state")
                .attributes(values -> values.put("code_verifier", "synthetic-verifier")).build();
        var response = OAuth2AuthorizationResponse.success("synthetic-code").redirectUri("https://app.example/callback")
                .state("synthetic-state").build();
        return new OAuth2AuthorizationCodeGrantRequest(registration, new OAuth2AuthorizationExchange(authorization, response));
    }
}
