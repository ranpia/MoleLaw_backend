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
    private static final String SECRET = "test-secret-key-with-at-least-sixty-four-bytes-for-algorithm-validation";
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
                .claim("tokenType", type).claim("tokenVersion", 3)
                .setIssuer("molelaw-auth").setAudience("molelaw-api")
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + lifetime))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void rejectsOldSubjectsInvalidIdsAndOldTokenVersionsForBothPurposes() {
        for (String type : new String[]{"access", "refresh"}) {
            for (String subject : new String[]{"user@example.com:local", "0", "-1", "01", "+1", " 1",
                    "1.0", "1e2", "9223372036854775808", "", "１"}) {
                String token = subjectToken(subject, type, 3);
                assertThat(jwtUtil.validateAccessToken(token)).isFalse();
            }
            for (Integer version : new Integer[]{null, 1, 2}) {
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
            assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "tokenType", "tokenVersion", "jti", "iat", "exp", "iss", "aud");
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
                .setIssuer("molelaw-auth").setAudience("molelaw-api")
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void checksIssuerAudienceAlgorithmLifetimeAndEveryRequiredClaim() {
        long now = System.currentTimeMillis() / 1000 * 1000;
        assertThat(jwtUtil.validateAccessToken(customToken("molelaw-auth", "molelaw-api", 3,
                now - 1000, now + 299000, SignatureAlgorithm.HS256, "jti"))).isTrue();
        assertThat(jwtUtil.validateAccessToken(customToken("other", "molelaw-api", 3,
                now, now + 300000, SignatureAlgorithm.HS256, "jti"))).isFalse();
        assertThat(jwtUtil.validateAccessToken(customToken("molelaw-auth", "other", 3,
                now, now + 300000, SignatureAlgorithm.HS256, "jti"))).isFalse();
        for (int version : new int[]{1, 2}) {
            assertThat(jwtUtil.validateAccessToken(customToken("molelaw-auth", "molelaw-api", version,
                    now, now + 300000, SignatureAlgorithm.HS256, "jti"))).isFalse();
        }
        for (long[] times : new long[][]{{now, now + 301000}, {now + 60000, now + 120000},
                {now - 300000, now}, {now, now - 1000}}) {
            assertThat(jwtUtil.validateAccessToken(customToken("molelaw-auth", "molelaw-api", 3,
                    times[0], times[1], SignatureAlgorithm.HS256, "jti"))).isFalse();
        }
        for (String missing : new String[]{"iss", "aud", "sub", "jti", "iat", "exp", "tokenType", "tokenVersion"}) {
            var builder = Jwts.builder().setIssuer("molelaw-auth").setAudience("molelaw-api")
                    .setSubject("1").setId("required-claim-test").claim("tokenType", "access")
                    .claim("tokenVersion", 3).setIssuedAt(new Date(now)).setExpiration(new Date(now + 300000));
            builder.claim(missing, null);
            String token = builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                    SignatureAlgorithm.HS256).compact();
            assertThat(jwtUtil.validateAccessToken(token)).as(missing).isFalse();
        }
        assertThat(jwtUtil.validateAccessToken(customToken("molelaw-auth", "molelaw-api", 3,
                now, now + 300000, SignatureAlgorithm.HS384, "jti"))).isFalse();
    }

    private String customToken(String issuer, String audience, int version, long issued, long expires,
                               SignatureAlgorithm algorithm, String id) {
        return Jwts.builder().setIssuer(issuer).setAudience(audience).setSubject("1").setId(id)
                .claim("tokenType", "access").claim("tokenVersion", version)
                .setIssuedAt(new Date(issued)).setExpiration(new Date(expires))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), algorithm).compact();
    }
}
