package com.MoleLaw_backend.user.entity;

import com.MoleLaw_backend.chat.entity.ChatRoom;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(
        name = "user",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_email_provider", columnNames = {"email", "provider"}),
                @UniqueConstraint(name = "uk_user_provider_subject", columnNames = {"provider", "provider_subject"})
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(nullable = false, length = 16)
    private String provider;

    @Column(name = "provider_subject", length = 255)
    private String providerSubject;

    // ✅ ChatRoom 연관관계 추가
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ChatRoom> chatRooms = new ArrayList<>();

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    @PreUpdate
    void validateAccount() {
        email = normalizeEmail(email);
        requireText(email, 254, "email");
        requireText(nickname, 50, "nickname");
        if (provider == null || !Set.of("local", "google", "kakao").contains(provider)) {
            throw new IllegalArgumentException("Unsupported account provider");
        }
        if ("local".equals(provider)) {
            requireText(password, 255, "password hash");
            if (providerSubject != null) {
                throw new IllegalArgumentException("Local accounts cannot have an OAuth subject");
            }
        } else {
            requireText(providerSubject, 255, "provider subject");
            if (password != null) {
                throw new IllegalArgumentException("OAuth accounts cannot have a local password");
            }
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("Invalid account " + field);
        }
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void changeNickname(String newNickname) {
        this.nickname = newNickname;
    }

}
