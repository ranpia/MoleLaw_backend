package com.MoleLaw_backend.auth.security;

import java.time.Instant;

public interface AccessTokenStore {
    void register(String tokenId, String subject, Instant expiresAt);

    boolean isActive(String tokenId, String subject);

    void revoke(String tokenId);
}
