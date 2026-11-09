package com.MoleLaw_backend.dto.security;

import java.time.Instant;

public record RefreshTokenGrant(String value, Long userId, Instant expiresAt) {
    @Override
    public String toString() {
        return "RefreshGrant[userId=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
