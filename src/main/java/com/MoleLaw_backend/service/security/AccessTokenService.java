package com.MoleLaw_backend.service.security;

import com.MoleLaw_backend.util.JwtUtil;

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
