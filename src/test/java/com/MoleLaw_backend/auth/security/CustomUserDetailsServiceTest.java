package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(users);

    @Test
    void loadsPrincipalByPrimaryKeyRegardlessOfEmailAndProvider() {
        when(users.findById(42L)).thenReturn(Optional.of(User.builder().id(42L)
                .email("changed@example.com").provider("google").build()));
        CustomUserDetails principal = (CustomUserDetails) service.loadUserByUsername("42");
        assertThat(principal.getUserId()).isEqualTo(42L);
        assertThat(principal.getUsername()).isEqualTo("42");
        assertThat(principal.getEmail()).isEqualTo("changed@example.com");
        verify(users).findById(42L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void rejectsLegacyAndInvalidIdentifiersBeforeQueryingTheDatabase() {
        for (String id : new String[]{"u@example.com:local", "0", "-1", "01", "9223372036854775808", ""}) {
            assertThatThrownBy(() -> service.loadUserByUsername(id)).isInstanceOf(UsernameNotFoundException.class);
        }
        verifyNoInteractions(users);
    }

    @Test
    void deletedUserIdIsNotMappedToAnotherAccount() {
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("42")).isInstanceOf(UsernameNotFoundException.class);
        verify(users).findById(42L);
        verifyNoMoreInteractions(users);
    }
}
