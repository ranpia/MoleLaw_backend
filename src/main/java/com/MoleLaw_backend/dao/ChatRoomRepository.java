package com.MoleLaw_backend.dao;

import com.MoleLaw_backend.entity.ChatRoom;
import com.MoleLaw_backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select room from ChatRoom room where room.id = :id")
    java.util.Optional<ChatRoom> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    List<ChatRoom> findByUserOrderByCreatedAtDescIdDesc(User user, Pageable pageable);
}
