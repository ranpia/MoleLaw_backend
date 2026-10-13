package com.MoleLaw_backend.service.security;

import io.jsonwebtoken.JwtException;
import com.MoleLaw_backend.exception.MolelawException;
import com.MoleLaw_backend.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
    private final AccessTokenService accessTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = null;

        // ✅ 1. Authorization 헤더에서 꺼내기
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            token = bearerToken.substring(7);
        }

        // ✅ 2. 없으면 쿠키에서 accessToken 꺼내기
        if (token == null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("accessToken".equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }


        // ✅ 3. 토큰 검증 및 사용자 인증 설정
        if (token != null && jwtUtil.validateAccessToken(token)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (accessTokenService.isActive(token)) {
                    String userId = jwtUtil.getUserIdFromAccessToken(token); // email:provider

                    CustomUserDetails userDetails = (CustomUserDetails) userDetailsService.loadUserByUsername(userId);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }

            } catch (MolelawException e) {
                if (e.getErrorCode() != ErrorCode.AUTH_STORE_UNAVAILABLE) {
                    throw e;
                }
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"error\":{\"status\":503,\"code\":\"AUTH_STORE_UNAVAILABLE\",\"message\":\"인증 저장소를 사용할 수 없습니다. 잠시 후 다시 시도하세요.\"}}");
                return;
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        return path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/oauth2/")
                || path.startsWith("/login/oauth2/")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/signup")
                || path.equals("/api/auth/csrf")
                || path.equals("/api/auth/reissue")
                || path.equals("/api/auth/logout");  // ✅ "/api/auth/me" 절대 금지!
    }
}
