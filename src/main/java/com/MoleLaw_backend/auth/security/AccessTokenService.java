package com.MoleLaw_backend.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessTokenService {
    private final JwtUtil jwtUtil;

    public String issue(Long userId) {
        return jwtUtil.generateAccessToken(userId);
    }
}
