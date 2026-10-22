package com.MoleLaw_backend.chat.repository;

import com.MoleLaw_backend.chat.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByChatRoomIdOrderByTimestampAscIdAsc(Long chatRoomId);

    Optional<Message> findFirstByChatRoomIdOrderByTimestampAscIdAsc(Long chatRoomId);

    long countByChatRoomId(Long chatRoomId);

}
