package com.MoleLaw_backend.chat.repository;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    Page<ChatRoom> findByUserOrderByCreatedAtDescIdDesc(User user, Pageable pageable);
}
