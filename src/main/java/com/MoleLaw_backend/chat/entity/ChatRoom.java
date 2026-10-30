package com.MoleLaw_backend.chat.entity;

import com.MoleLaw_backend.user.entity.User;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ChatRoom {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        validate();
        this.createdAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    @PreUpdate
    public void validate() {
        if (user == null || title == null || title.isBlank() || title.codePointCount(0, title.length()) > 255) {
            throw new IllegalArgumentException("Chat room requires an owner and a title of 1–255 characters");
        }
    }

    public void changeTitle(String title) {
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 255)
            throw new IllegalArgumentException("Invalid chat room title");
        this.title = title;
    }

}
