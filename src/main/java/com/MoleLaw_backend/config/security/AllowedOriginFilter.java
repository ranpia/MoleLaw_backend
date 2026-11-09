package com.MoleLaw_backend.config.security;

import com.MoleLaw_backend.controller.GlobalExceptionHandler;
import com.MoleLaw_backend.util.exception.ErrorCode;
import org.springframework.security.access.AccessDeniedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** Additional origin check; missing Origin still requires the normal CSRF token. */
public class AllowedOriginFilter extends OncePerRequestFilter {
    private final List<String> allowedOrigins;
    private final GlobalExceptionHandler errors;

    public AllowedOriginFilter(List<String> allowedOrigins,
            GlobalExceptionHandler errors) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
        this.errors = errors;
    }

    public static List<String> parseOrigins(String configuredOrigins) {
        return Arrays.stream(configuredOrigins.split(",", -1)).map(String::trim).map(value -> {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw new IllegalArgumentException("Allowed origins must contain explicit HTTP(S) origins");
            }
            return uri.getScheme() + "://" + uri.getRawAuthority();
        }).distinct().toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                && origin != null && CorsUtils.isCorsRequest(request) && !allowedOrigins.contains(origin)) {
            errors.handle(request, response, new AccessDeniedException("Origin rejected"));
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } catch (Exception failure) {
            // Filter/OAuth failures occur outside MVC advice; use the same response boundary.
            errors.writeError(response, failure, ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
