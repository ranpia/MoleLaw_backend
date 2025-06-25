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
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("해당 이메일을 찾을 수 없습니다: " + email));

        String password = (user.getPassword() == null || user.getPassword().isEmpty()) ? "{noop}" : user.getPassword();

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(password) // "{noop}" 넣어야 Security 검증 통과
                .authorities("ROLE_USER")
                .build();
    }
}
