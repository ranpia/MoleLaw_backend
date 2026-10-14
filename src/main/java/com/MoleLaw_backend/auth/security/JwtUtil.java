package com.MoleLaw_backend.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;

@Component
public class JwtUtil {
    private final Duration accessTtl;
    private static final String TOKEN_TYPE = "tokenType";
    private final String secretKeyRaw;
    private Key key;

    public JwtUtil(@Value("${jwt.secret}") String secretKeyRaw,
                   @Value("${jwt.access-ttl:5m}") Duration accessTtl) {
        if (secretKeyRaw == null || secretKeyRaw.isBlank()) {
            throw new IllegalArgumentException("JWT_SECRET 환경변수가 설정되지 않았습니다.");
        }
        this.secretKeyRaw = secretKeyRaw;
        if (accessTtl == null || accessTtl.isNegative() || accessTtl.isZero()
                || accessTtl.getNano() != 0) {
            throw new IllegalArgumentException("Access token TTL must be positive whole seconds");
        }
        this.accessTtl = accessTtl;
    }

    @PostConstruct
    public void init() {
        key = Keys.hmacShaKeyFor(secretKeyRaw.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long userId) {
        return generateToken(userId, "access", accessTtl.toMillis());
    }

    private String generateToken(Long userId, String type, long lifetime) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("A persisted positive user ID is required");
        }
        Date now = new Date();
        return Jwts.builder().setSubject(userId.toString()).claim(TOKEN_TYPE, type)
                .claim("tokenVersion", 2)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(now).setExpiration(new Date(now.getTime() + lifetime))
                .signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public boolean validateAccessToken(String token) {
        return validateToken(token, "access");
    }

    private boolean validateToken(String token, String type) {
        try {
            parseClaims(token, type);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token, String type) {
        Jws<Claims> parsed = Jwts.parserBuilder().setSigningKey(key)
                .require(TOKEN_TYPE, type).require("tokenVersion", 2).build().parseClaimsJws(token);
        Claims claims = parsed.getBody();
        if (!SignatureAlgorithm.HS256.getValue().equals(parsed.getHeader().getAlgorithm())
                || claims.getExpiration() == null || claims.getIssuedAt() == null
                || claims.getId() == null || claims.getId().isBlank()) {
            throw new IllegalArgumentException("유효하지 않은 토큰입니다.");
        }
        parseUserId(claims.getSubject());
        return claims;
    }

    public Long getUserIdFromAccessToken(String token) {
        return parseUserId(parseClaims(token, "access").getSubject());
    }

    public AccessTokenIdentity getAccessTokenIdentity(String token) {
        Claims claims = parseClaims(token, "access");
        return new AccessTokenIdentity(claims.getId(), claims.getSubject(), claims.getExpiration().toInstant());
    }

    public record AccessTokenIdentity(String tokenId, String subject, Instant expiresAt) {}

    public static Long parseUserId(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Invalid user ID");
        }
        return Long.parseLong(subject);
    }

    public String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("accessToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
