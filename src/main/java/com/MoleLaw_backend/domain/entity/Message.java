package com.MoleLaw_backend.domain.entity;

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
    private com.MoleLaw_backend.domain.entity.ChatRoom chatRoom;

    @Enumerated(EnumType.STRING)
    private Sender sender; // USER or BOT

    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime timestamp;

    @PrePersist
    public void prePersist() {
        this.timestamp = LocalDateTime.now();
    }

    public enum Sender {
        USER, BOT
    }
}
