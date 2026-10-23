package com.MoleLaw_backend.chat.repository;

import com.MoleLaw_backend.chat.entity.ChatCaseEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChatCaseEvidenceRepository extends JpaRepository<ChatCaseEvidence, String> {
    List<ChatCaseEvidence> findByRoomIdOrderByCapturedAtAscIdAsc(Long roomId);
}
