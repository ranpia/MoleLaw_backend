package com.MoleLaw_backend.auth.security;

import java.time.Duration;

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
        jwtUtil = new JwtUtil(SECRET, Duration.ofMinutes(5));
        jwtUtil.init();
    }

    @Test
    void acceptsOnlyTheIntendedTokenPurpose() {
        String access = jwtUtil.generateAccessToken(1L);
        String refresh = TestJwtTokens.legacyRefresh(1L);
        assertThat(jwtUtil.validateAccessToken(access)).isTrue();
        assertThat(jwtUtil.validateAccessToken(refresh)).isFalse();
        assertThat(jwtUtil.getUserIdFromAccessToken(access)).isEqualTo(1L);
    }

    @Test
    void rejectsLegacyUnknownExpiredAndMalformedTokens() {
        assertThat(jwtUtil.validateAccessToken(signedToken(null, SECRET, 60000))).isFalse();
        assertThat(jwtUtil.validateAccessToken(signedToken("other", SECRET, 60000))).isFalse();
        assertThat(jwtUtil.validateAccessToken(signedToken("access", SECRET, -60000))).isFalse();
        assertThat(jwtUtil.validateAccessToken("malformed")).isFalse();
        assertThat(jwtUtil.validateAccessToken(null)).isFalse();
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() {
        String token = signedToken("access", "different-secret-key-with-at-least-thirty-two-bytes", 60000);
        assertThat(jwtUtil.validateAccessToken(token)).isFalse();
    }

    private String signedToken(String type, String secret, long lifetime) {
        return Jwts.builder().setSubject("1").setId("test-token-id")
                .claim("tokenType", type).claim("tokenVersion", 2)
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + lifetime))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void rejectsOldSubjectsInvalidIdsAndOldTokenVersionsForBothPurposes() {
        for (String type : new String[]{"access", "refresh"}) {
            for (String subject : new String[]{"user@example.com:local", "0", "-1", "01", "+1", " 1",
                    "1.0", "1e2", "9223372036854775808", "", "１"}) {
                String token = subjectToken(subject, type, 2);
                assertThat(jwtUtil.validateAccessToken(token)).isFalse();
            }
            for (Integer version : new Integer[]{null, 1, 3}) {
                String token = subjectToken("1", type, version);
                assertThat(jwtUtil.validateAccessToken(token)).isFalse();
            }
        }
    }

    @Test
    void includesOnlyMinimalClaimsAndAcceptsTheLargestDatabaseId() {
        for (String token : new String[]{jwtUtil.generateAccessToken(Long.MAX_VALUE)}) {
            var claims = Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                    .build().parseClaimsJws(token).getBody();
            assertThat(claims.getSubject()).isEqualTo(Long.toString(Long.MAX_VALUE));
            assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "tokenType", "tokenVersion", "jti", "iat", "exp");
        }
    }

    @Test
    void refusesIssuanceWithoutAPersistedPositiveUserId() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jwtUtil.generateAccessToken(id))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String subjectToken(String subject, String type, Integer version) {
        return Jwts.builder().setSubject(subject).setId("subject-validation-id")
                .claim("tokenType", type).claim("tokenVersion", version)
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }
}
