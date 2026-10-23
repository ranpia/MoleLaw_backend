package com.MoleLaw_backend.chat.entity;

import com.MoleLaw_backend.law.entity.LegalSource;
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

/** Append-only precedent references; the first law snapshot is never rewritten. */
@Entity
@Table(name = "chat_case_evidence", uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_case_room_external", columnNames = {"room_id", "external_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatCaseEvidence {
    @Id
    @Column(length = 36, updatable = false)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, updatable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private ChatRoom room;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false, updatable = false)
    private LegalSource source;
    @Column(name = "external_id", length = 20, nullable = false, updatable = false)
    private String externalId;
    @Column(nullable = false, updatable = false)
    private Instant capturedAt;

    public ChatCaseEvidence(String id, ChatRoom room, LegalSource source) {
        this.id = id;
        this.room = room;
        this.source = source;
        this.externalId = source.getExternalId();
        this.capturedAt = Instant.now();
    }
}
