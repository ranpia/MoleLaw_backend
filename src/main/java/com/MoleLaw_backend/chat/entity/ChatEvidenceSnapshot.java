package com.MoleLaw_backend.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "chat_evidence_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatEvidenceSnapshot implements Persistable<Long> {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    @Id
    @Column(name = "room_id", updatable = false)
    private Long roomId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, updatable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private ChatRoom room;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT", updatable = false)
    private String evidenceJson;

    @Column(nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Column(nullable = false, updatable = false)
    private Instant capturedAt;

    @Transient
    private boolean newEntity = true;

    @Override
    public Long getId() {
        return roomId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markPersisted() {
        newEntity = false;
    }

    public ChatEvidenceSnapshot(ChatRoom room, String evidenceJson, String contentHash) {
        this.room = room;
        this.roomId = room.getId();
        this.evidenceJson = evidenceJson;
        this.contentHash = contentHash;
        this.capturedAt = Instant.now();
    }
}
