package com.MoleLaw_backend.user.service;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.MoleLaw_backend.user.dto.request.ChangeNicknameRequest;
import com.MoleLaw_backend.user.dto.request.ChangePasswordRequest;
import com.MoleLaw_backend.auth.dto.response.AuthResponse;
import com.MoleLaw_backend.auth.dto.request.LoginRequest;
import com.MoleLaw_backend.auth.dto.request.SignupRequest;
import com.MoleLaw_backend.user.dto.response.UserResponse;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.auth.security.CookieUtil;
import com.MoleLaw_backend.auth.security.JwtUtil;
import com.MoleLaw_backend.auth.security.AccessTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final CookieUtil cookieUtil;
    private final AccessTokenService accessTokenService;

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByEmailAndProvider(request.getEmail(), "local")) {
            throw new MolelawException(ErrorCode.DUPLICATED_EMAIL);
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .provider("local")
                .build();

        user = userRepository.save(user);

        String accessToken = accessTokenService.issue(user.getId());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        return new AuthResponse(accessToken, refreshToken);
    }

    public AuthResponse login(LoginRequest request, HttpServletResponse response) {
        User user = userRepository.findByEmailAndProvider(request.getEmail(), "local")
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND,"해당 이메일이 존재하지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MolelawException(ErrorCode.PASSWORD_FAIL);
        }

        String accessToken = accessTokenService.issue(user.getId());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        AuthResponse authResponse=  new AuthResponse(accessToken, refreshToken);
        cookieUtil.addJwtCookie(response, "accessToken", authResponse.getAccessToken());
        cookieUtil.addJwtCookie(response, "refreshToken", authResponse.getRefreshToken());
        return authResponse;
    }

    public AuthResponse reissue(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieUtil.getTokenFromCookie(request, "refreshToken");

        if (refreshToken == null || !jwtUtil.validateRefreshToken(refreshToken)) {
            throw new MolelawException(ErrorCode.TOKEN_FAIL);
        }

        Long userId = jwtUtil.getUserIdFromRefreshToken(refreshToken);
        userRepository.findById(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.TOKEN_FAIL));

        String newAccessToken = accessTokenService.issue(userId);
        cookieUtil.addJwtCookie(response, "accessToken", newAccessToken);

        return new AuthResponse(newAccessToken, refreshToken);
    }


    public UserResponse getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));
        return new UserResponse(user);
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));
        userRepository.delete(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));

        if (!"local".equals(user.getProvider())) {
            throw new MolelawException(ErrorCode.INVALID_PROVIDER, "비밀번호 변경은 소셜로그인 대상자에게 제공하지 않습니다.");
        }

        user.changePassword(passwordEncoder.encode(request.getNewPassword()));
    }


    @Transactional
    public void changeNickname(Long userId, ChangeNicknameRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));

        user.changeNickname(request.getNewNickname());
    }


}

