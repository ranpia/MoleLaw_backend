package com.MoleLaw_backend.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** Room-level removal leaves the immutable initial snapshot and shared source untouched. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_evidence_removal", uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_evidence_removal_room_evidence", columnNames = {"room_id", "evidence_id"}))
public class ChatEvidenceRemoval {
    @Id
    @Column(length = 36, updatable = false)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, updatable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private ChatRoom room;
    @Column(name = "evidence_id", nullable = false, length = 36, updatable = false)
    private String evidenceId;
    @Column(nullable = false, updatable = false)
    private Instant removedAt;

    public ChatEvidenceRemoval(ChatRoom room, String evidenceId) {
        this.id = UUID.randomUUID().toString();
        this.room = room;
        this.evidenceId = evidenceId;
        this.removedAt = Instant.now();
    }
}
