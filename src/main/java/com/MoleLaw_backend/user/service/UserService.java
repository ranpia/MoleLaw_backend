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
import com.MoleLaw_backend.auth.security.AuthTokenService;
import com.MoleLaw_backend.auth.security.RefreshTokenService;
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
    private final CookieUtil cookieUtil;
    private final AuthTokenService authTokens;
    private final RefreshTokenService refreshTokens;

    @Transactional
    public AuthResponse signup(SignupRequest request, HttpServletResponse response) {
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

        return authTokens.issue(user.getId(), response);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletResponse response) {
        User user = userRepository.findByCredentialsForUpdate(request.getEmail(), "local")
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND,"해당 이메일이 존재하지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MolelawException(ErrorCode.PASSWORD_FAIL);
        }

        return authTokens.issue(user.getId(), response);
    }

    @Transactional
    public AuthResponse reissue(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieUtil.getTokenFromCookie(request, "refreshToken");

        Long userId = refreshTokens.userId(refreshToken);
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.INVALID_REFRESH_TOKEN));
        return authTokens.rotate(refreshToken, response);
    }


    public UserResponse getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));
        return new UserResponse(user);
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));
        refreshTokens.revokeAll(userId);
        userRepository.delete(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));

        if (!"local".equals(user.getProvider())) {
            throw new MolelawException(ErrorCode.INVALID_PROVIDER, "비밀번호 변경은 소셜로그인 대상자에게 제공하지 않습니다.");
        }

        refreshTokens.revokeAll(userId);
        user.changePassword(passwordEncoder.encode(request.getNewPassword()));
    }


    @Transactional
    public void changeNickname(Long userId, ChangeNicknameRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));

        user.changeNickname(request.getNewNickname());
    }


}

