package com.MoleLaw_backend.service;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
import com.MoleLaw_backend.dto.response.AuthResponse;
import com.MoleLaw_backend.dto.request.LoginRequest;
import com.MoleLaw_backend.dto.request.SignupRequest;
import com.MoleLaw_backend.dto.response.UserResponse;
import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.MolelawException;
import com.MoleLaw_backend.service.security.CookieUtil;
import com.MoleLaw_backend.service.security.JwtUtil;
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

    public AuthResponse signup(SignupRequest request) {
//        if (userRepository.existsByEmail(request.getEmail())) {
//            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
//        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new MolelawException(ErrorCode.DUPLICATED_EMAIL);
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .provider("local")
                .build();

        userRepository.save(user);

        String accessToken = jwtUtil.generateAccessToken(user.getEmail(), "local");
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail(), "local");

        return new AuthResponse(accessToken, refreshToken);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("해당 이메일이 존재하지 않습니다."));

//        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
//            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
//        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MolelawException(ErrorCode.PASSWORD_FAIL);
        }

        // ✅ 여기서 provider를 반드시 명시
        String accessToken = jwtUtil.generateAccessToken(user.getEmail(), user.getProvider());
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail(), user.getProvider());

        return new AuthResponse(accessToken, refreshToken);
    }

    public AuthResponse login(LoginRequest request, HttpServletResponse response) {
        AuthResponse authResponse = login(request);
        cookieUtil.addJwtCookie(response, "accessToken", authResponse.getAccessToken(), true);
        cookieUtil.addJwtCookie(response, "refreshToken", authResponse.getRefreshToken(), true);
        return authResponse;
    }

    public AuthResponse reissue(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieUtil.getTokenFromCookie(request, "refreshToken");

//        if (refreshToken == null || !jwtUtil.validateToken(refreshToken)) {
//            throw new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다.");
//        }
        if (refreshToken == null || !jwtUtil.validateToken(refreshToken)) {
            throw new MolelawException(ErrorCode.TOKEN_FAIL);
        }

        String[] subjectParts = jwtUtil.getEmailAndProviderFromToken(refreshToken);
        String email = subjectParts[0];
        String provider = subjectParts[1];

        String newAccessToken = jwtUtil.generateAccessToken(email, provider);
        cookieUtil.addJwtCookie(response, "accessToken", newAccessToken, true);

        return new AuthResponse(newAccessToken, refreshToken);
    }

    public UserResponse getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));
        return new UserResponse(user);
    }

    public UserResponse getUserByEmailAndProvider(String email, String provider) {
        User user = userRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));
        return new UserResponse(user);
    }

    @Transactional
    public void deleteUser(String email, String provider) {
        System.out.println("🧪 deleteUser 호출됨 → " + email + " / " + provider);

        User user = userRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> {
                    System.out.println("❌ 유저를 찾을 수 없음 → email: " + email + ", provider: " + provider);
                    return new MolelawException(ErrorCode.USER_NOT_FOUND, email + " / " + provider);
                });

        System.out.println("✅ 유저 조회 성공 → id: " + user.getId());

        userRepository.deleteById(user.getId()); // 여기만 수정!

        System.out.println("🗑️ 유저 삭제 완료");
    }


}

