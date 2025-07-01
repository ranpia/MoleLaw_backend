package com.MoleLaw_backend.service.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secretKeyRaw;

    private Key key;

    private final long ACCESS_EXPIRATION = 1000 * 60 * 15;       // 15분
    private final long REFRESH_EXPIRATION = 1000 * 60 * 60 * 24 * 7; // 7일

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secretKeyRaw.getBytes(StandardCharsets.UTF_8));
        System.out.println("✅ JwtUtil 초기화 완료 (key ready)");
    }

    public String generateAccessToken(String email, String provider) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ACCESS_EXPIRATION);

        return Jwts.builder()
                .setSubject(email + ":" + provider)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateRefreshToken(String email, String provider) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + REFRESH_EXPIRATION);

        return Jwts.builder()
                .setSubject(email + ":" + provider)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String getUserIdFromToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (Exception e) {
            throw new IllegalArgumentException("토큰 파싱에 실패했습니다: " + e.getMessage());
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            System.out.println("⏰ 만료된 토큰입니다: " + e.getMessage());
        } catch (UnsupportedJwtException e) {
            System.out.println("🚫 지원하지 않는 토큰입니다: " + e.getMessage());
        } catch (MalformedJwtException e) {
            System.out.println("❌ 잘못된 형식의 토큰입니다: " + e.getMessage());
        } catch (SignatureException e) {
            System.out.println("🔐 서명이 올바르지 않습니다: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("⚠️ 잘못된 요청입니다: " + e.getMessage());
        }
        return false;
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

    public String[] getEmailAndProviderFromToken(String token) {
        String subject = getUserIdFromToken(token); // ex: "user@naver.com:google"
        return subject.split(":");
    }
}
