package com.MoleLaw_backend.service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessTokenService {
    private final JwtUtil jwtUtil;
    private final AccessTokenStore store;

    public String issue(String email, String provider) {
        String token = jwtUtil.generateAccessToken(email, provider);
        var identity = jwtUtil.getAccessTokenIdentity(token);
        store.register(identity.tokenId(), identity.subject(), identity.expiresAt());
        return token;
    }

    public boolean isActive(String token) {
        var identity = jwtUtil.getAccessTokenIdentity(token);
        return store.isActive(identity.tokenId(), identity.subject());
    }

    public void revoke(String token) {
        if (token != null && jwtUtil.validateAccessToken(token)) {
            store.revoke(jwtUtil.getAccessTokenIdentity(token).tokenId());
        }
    }
}
