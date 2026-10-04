package com.MoleLaw_backend.service.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    private final String secretKeyRaw;
    private Key key;

    private final long ACCESS_EXPIRATION = 1000 * 60 * 15 * 60;
    private final long REFRESH_EXPIRATION = 1000 * 60 * 60 * 24 * 7;

    public JwtUtil(@Value("${jwt.secret}") String secretKeyRaw) {
        this.secretKeyRaw = secretKeyRaw;
        if (this.secretKeyRaw == null || this.secretKeyRaw.isBlank()) {
            throw new IllegalArgumentException("❌ JWT_SECRET 환경변수가 설정되지 않았습니다.");
        }
    }

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secretKeyRaw.getBytes(StandardCharsets.UTF_8));
        System.out.println("✅ JwtUtil 초기화 완료 (key ready)");
    }

    public String generateAccessToken(String email, String provider) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ACCESS_EXPIRATION);

        String subject = email + ":" + provider;
        System.out.println("🔐 accessToken 생성 → subject: " + subject);

        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateRefreshToken(String email, String provider) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + REFRESH_EXPIRATION);

        String subject = email + ":" + provider;
        System.out.println("🔐 refreshToken 생성 → subject: " + subject);

        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String getUserIdFromToken(String token) {
        try {
            String subject = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();

            System.out.println("🔎 getUserIdFromToken → subject: " + subject);
            return subject;

        } catch (Exception e) {
            System.out.println("❌ getUserIdFromToken 예외: " + e.getMessage());
            throw new IllegalArgumentException("토큰 파싱에 실패했습니다: " + e.getMessage());
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);
            System.out.println("✅ 토큰 유효성 검증 성공");
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

        debugToken(token); // 실패 시 디버깅 로그 추가
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
        String subject = getUserIdFromToken(token);
        System.out.println("📦 getEmailAndProviderFromToken → " + subject);
        return subject.split(":");
    }

    public void debugToken(String token) {
        System.out.println("🧪 디버그용 토큰 분석 시작");
        try {
            var parsed = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            System.out.println("✅ 디버그: payload = " + parsed.getBody());
        } catch (Exception e) {
            System.out.println("❌ 디버그 실패: " + e.getMessage());
        }
    }
}
