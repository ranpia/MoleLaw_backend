package com.MoleLaw_backend.chat.repository;

import com.MoleLaw_backend.chat.entity.ChatEvidenceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatEvidenceSnapshotRepository extends JpaRepository<ChatEvidenceSnapshot, Long> {
}
