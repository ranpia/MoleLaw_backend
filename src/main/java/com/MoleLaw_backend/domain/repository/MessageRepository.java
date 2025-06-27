package com.MoleLaw_backend.domain.repository;

import com.MoleLaw_backend.domain.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByChatRoomIdOrderByTimestampAsc(Long chatRoomId);

    Optional<Message> findFirstByChatRoomIdOrderByTimestampAsc(Long chatRoomId);

    long countByChatRoomId(Long chatRoomId);
}
