package com.MoleLaw_backend.chat.repository;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    List<ChatRoom> findByUser(User user);
}
