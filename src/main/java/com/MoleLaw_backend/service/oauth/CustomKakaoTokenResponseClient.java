package com.MoleLaw_backend.service.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
public class CustomKakaoTokenResponseClient implements OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> {

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public OAuth2AccessTokenResponse getTokenResponse(OAuth2AuthorizationCodeGrantRequest request) {
        var clientRegistration = request.getClientRegistration();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> formParams = new LinkedMultiValueMap<>();
        formParams.add("grant_type", "authorization_code");
        formParams.add("client_id", clientRegistration.getClientId());
        formParams.add("redirect_uri", clientRegistration.getRedirectUri());
        formParams.add("code", request.getAuthorizationExchange().getAuthorizationResponse().getCode());
        if (clientRegistration.getClientSecret() != null) {
            formParams.add("client_secret", clientRegistration.getClientSecret());
        }

        HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(formParams, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                clientRegistration.getProviderDetails().getTokenUri(),
                HttpMethod.POST,
                httpEntity,
                Map.class
        );

        Map<String, Object> responseBody = response.getBody();

        return OAuth2AccessTokenResponse.withToken((String) responseBody.get("access_token"))
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(((Number) responseBody.get("expires_in")).longValue())
                .scopes(request.getClientRegistration().getScopes())
                .refreshToken((String) responseBody.get("refresh_token"))
                .build();
    }
}
