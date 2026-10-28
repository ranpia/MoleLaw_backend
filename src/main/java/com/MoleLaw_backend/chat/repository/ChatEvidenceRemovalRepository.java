package com.MoleLaw_backend.chat.repository;

import com.MoleLaw_backend.chat.entity.ChatEvidenceRemoval;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChatEvidenceRemovalRepository extends JpaRepository<ChatEvidenceRemoval, String> {
    List<ChatEvidenceRemoval> findByRoomId(Long roomId);
    boolean existsByRoomIdAndEvidenceId(Long roomId, String evidenceId);
}
