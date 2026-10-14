package com.MoleLaw_backend.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Constructs the former JWT Refresh format solely to verify migration rejection. */
public final class TestJwtTokens {
    private TestJwtTokens() {}

    public static String legacyRefresh(Long userId) {
        return Jwts.builder().setSubject(userId.toString()).setId("legacy-test-token")
                .claim("tokenType", "refresh").claim("tokenVersion", 2)
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + 604800000L))
                .signWith(Keys.hmacShaKeyFor("test-secret-key-with-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256).compact();
    }
}
