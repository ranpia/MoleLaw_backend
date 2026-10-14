package com.MoleLaw_backend.auth.security;

import java.time.Instant;

public interface RefreshTokenStore {
    long generation(Long userId);
    void create(Long userId, String familyId, String hash, Instant expiresAt, long generation);
    Instant rotate(Long userId, String familyId, String previousHash, String nextHash);
    void revoke(Long userId, String familyId, String knownHash);
    void revokeAll(Long userId);
}
