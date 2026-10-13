package com.MoleLaw_backend.service.user;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
import com.MoleLaw_backend.dto.request.ChangeNicknameRequest;
import com.MoleLaw_backend.dto.request.ChangePasswordRequest;
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
        if (userRepository.existsByEmailAndProvider(request.getEmail(), "local")) {
            throw new MolelawException(ErrorCode.DUPLICATED_EMAIL);
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .provider("local")
                .build();

        userRepository.save(user);

        String accessToken = jwtUtil.generateAccessToken(user.getEmail(), user.getProvider());
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail(), user.getProvider());

        return new AuthResponse(accessToken, refreshToken);
    }

    public AuthResponse login(LoginRequest request, HttpServletResponse response) {
        User user = userRepository.findByEmailAndProvider(request.getEmail(), "local")
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND,"해당 이메일이 존재하지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MolelawException(ErrorCode.PASSWORD_FAIL);
        }

        // ✅ 여기서 provider를 반드시 명시
        String accessToken = jwtUtil.generateAccessToken(user.getEmail(), user.getProvider());
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail(), user.getProvider());

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

        String[] subjectParts = jwtUtil.getEmailAndProviderFromRefreshToken(refreshToken);
        String email = subjectParts[0];
        String provider = subjectParts[1];

        userRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new MolelawException(ErrorCode.TOKEN_FAIL));

        String newAccessToken = jwtUtil.generateAccessToken(email, provider);
        cookieUtil.addJwtCookie(response, "accessToken", newAccessToken);

        return new AuthResponse(newAccessToken, refreshToken);
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

        userRepository.delete(user); // ✅ 이제 연관된 ChatRoom + Message 전부 삭제됨

        System.out.println("🗑️ 유저 삭제 완료");
    }

    @Transactional
    public void changePassword(String email, String provider, ChangePasswordRequest request) {
        User user = userRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));

        if (!"local".equals(user.getProvider())) {
            throw new MolelawException(ErrorCode.INVALID_PROVIDER, "비밀번호 변경은 소셜로그인 대상자에게 제공하지 않습니다.");
        }

        user.changePassword(passwordEncoder.encode(request.getNewPassword()));
    }


    @Transactional
    public void changeNickname(String email, String provider, ChangeNicknameRequest request) {
        User user = userRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new MolelawException(ErrorCode.USER_NOT_FOUND));

        user.changeNickname(request.getNewNickname());
    }


}

