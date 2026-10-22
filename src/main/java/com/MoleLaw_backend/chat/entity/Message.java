package com.MoleLaw_backend.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Message {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private ChatRoom chatRoom;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private Sender sender;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @PrePersist
    public void prePersist() {
        validate();
        this.timestamp = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    @PreUpdate
    public void validate() {
        if (chatRoom == null || sender == null || sender == Sender.INFO || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Persisted messages require room, USER/BOT sender and encrypted content");
        }
    }

    public enum Sender {
        USER, BOT, INFO
    }
}
