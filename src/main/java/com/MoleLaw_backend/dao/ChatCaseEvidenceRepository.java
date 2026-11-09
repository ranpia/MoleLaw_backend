package com.MoleLaw_backend.dao;

import com.MoleLaw_backend.entity.ChatCaseEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChatCaseEvidenceRepository extends JpaRepository<ChatCaseEvidence, String> {
    List<ChatCaseEvidence> findByRoomIdOrderByCapturedAtAscIdAsc(Long roomId);
    java.util.Optional<ChatCaseEvidence> findByIdAndRoomId(String id, Long roomId);
}
