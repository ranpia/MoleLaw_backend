package com.MoleLaw_backend.service.security;

import com.MoleLaw_backend.dto.security.CustomUserDetails;
import com.MoleLaw_backend.util.JwtUtil;

import com.MoleLaw_backend.entity.User;
import com.MoleLaw_backend.dao.UserRepository;
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
        Long userId;
        try {
            userId = JwtUtil.parseUserId(identifier);
        } catch (IllegalArgumentException e) {
            throw new UsernameNotFoundException("올바르지 않은 사용자 식별자입니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("해당 사용자를 찾을 수 없습니다."));

        return new CustomUserDetails(user);
    }

}
