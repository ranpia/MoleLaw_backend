package com.MoleLaw_backend.chat.repository;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select room from ChatRoom room where room.id = :id")
    java.util.Optional<ChatRoom> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    Page<ChatRoom> findByUserOrderByCreatedAtDescIdDesc(User user, Pageable pageable);
}
