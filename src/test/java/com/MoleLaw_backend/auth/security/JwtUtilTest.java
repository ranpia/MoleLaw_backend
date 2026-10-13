package com.MoleLaw_backend.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {
    private static final String SECRET = "test-secret-key-with-at-least-thirty-two-bytes";
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET);
        jwtUtil.init();
    }

    @Test
    void acceptsOnlyTheIntendedTokenPurpose() {
        String access = jwtUtil.generateAccessToken("user@example.com", "local");
        String refresh = jwtUtil.generateRefreshToken("user@example.com", "local");
        assertThat(jwtUtil.validateAccessToken(access)).isTrue();
        assertThat(jwtUtil.validateRefreshToken(refresh)).isTrue();
        assertThat(jwtUtil.validateAccessToken(refresh)).isFalse();
        assertThat(jwtUtil.validateRefreshToken(access)).isFalse();
        assertThat(jwtUtil.getUserIdFromAccessToken(access)).isEqualTo("user@example.com:local");
        assertThat(jwtUtil.getEmailAndProviderFromRefreshToken(refresh))
                .containsExactly("user@example.com", "local");
    }

    @Test
    void rejectsLegacyUnknownExpiredAndMalformedTokens() {
        assertThat(jwtUtil.validateAccessToken(signedToken(null, SECRET, 60000))).isFalse();
        assertThat(jwtUtil.validateAccessToken(signedToken("other", SECRET, 60000))).isFalse();
        assertThat(jwtUtil.validateAccessToken(signedToken("access", SECRET, -60000))).isFalse();
        assertThat(jwtUtil.validateAccessToken("malformed")).isFalse();
        assertThat(jwtUtil.validateAccessToken(null)).isFalse();
        assertThat(jwtUtil.validateRefreshToken("")).isFalse();
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() {
        String token = signedToken("access", "different-secret-key-with-at-least-thirty-two-bytes", 60000);
        assertThat(jwtUtil.validateAccessToken(token)).isFalse();
    }

    private String signedToken(String type, String secret, long lifetime) {
        return Jwts.builder().setSubject("user@example.com:local").claim("tokenType", type)
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + lifetime))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }
}
