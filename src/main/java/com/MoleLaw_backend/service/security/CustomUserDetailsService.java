package com.MoleLaw_backend.service.security;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        // identifier는 "email:provider" 형식
        String[] parts = identifier.split(":");
        if (parts.length != 2) {
            throw new UsernameNotFoundException("올바르지 않은 사용자 식별자입니다: " + identifier);
        }

        String email = parts[0];
        String provider = parts[1];

        User user = userRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new UsernameNotFoundException("해당 이메일을 찾을 수 없습니다: " + identifier));

        String password = (user.getPassword() == null || user.getPassword().isEmpty()) ? "{noop}" : user.getPassword();

        return org.springframework.security.core.userdetails.User
                .withUsername(email + ":" + provider) // 토큰의 subject와 동일하게
                .password(password)
                .authorities("ROLE_USER")
                .build();
    }

}
