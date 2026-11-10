package com.MoleLaw_backend.dao;

import com.MoleLaw_backend.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByChatRoomIdOrderByTimestampAscIdAsc(Long chatRoomId);

    Optional<Message> findFirstByChatRoomIdOrderByTimestampAscIdAsc(Long chatRoomId);

    interface FirstMessagePreview {
        Long getRoomId();
        String getContent();
    }

    @Query("""
            select m.chatRoom.id as roomId, m.content as content from Message m
            where m.chatRoom.id in :roomIds
              and not exists (select earlier.id from Message earlier
                  where earlier.chatRoom.id = m.chatRoom.id
                    and (earlier.timestamp < m.timestamp
                        or (earlier.timestamp = m.timestamp and earlier.id < m.id)))
            """)
    List<FirstMessagePreview> findFirstMessagePreviews(@Param("roomIds") List<Long> roomIds);

    long countByChatRoomId(Long chatRoomId);

}
