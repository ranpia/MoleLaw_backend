package com.MoleLaw_backend.chat.service.evidence;

import com.MoleLaw_backend.chat.entity.ChatEvidenceSnapshot;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.repository.ChatEvidenceSnapshotRepository;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatEvidenceServiceTest {
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final ChatEvidenceSnapshotRepository snapshots = mock(ChatEvidenceSnapshotRepository.class);
    private final ChatEvidenceService service = new ChatEvidenceService(rooms, snapshots, new ObjectMapper());
    private ChatRoom room;

    @BeforeEach
    void prepareRoom() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        room = ChatRoom.builder().id(3L).user(user).build();
        when(rooms.findById(3L)).thenReturn(Optional.of(room));
    }

    @Test
    void freezesAndRestoresImmutableExactEvidence() {
        EvidenceItem item = evidence();
        service.freeze(room, List.of(item));
        var captured = ArgumentCaptor.forClass(ChatEvidenceSnapshot.class);
        verify(snapshots).saveAndFlush(captured.capture());
        ChatEvidenceSnapshot snapshot = captured.getValue();
        assertThat(snapshot.getRoomId()).isEqualTo(3L);
        assertThat(snapshot.getContentHash()).hasSize(64);
        assertThat(snapshot.getCapturedAt()).isNotNull();
        when(snapshots.findById(3L)).thenReturn(Optional.of(snapshot));
        List<EvidenceItem> restored = service.load(7L, 3L);
        assertThat(restored).containsExactly(item);
        assertThatThrownBy(() -> restored.add(item)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deniesOtherOwnerBeforeReadingSnapshot() {
        assertThatThrownBy(() -> service.load(8L, 3L))
                .isInstanceOfSatisfying(MolelawException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS));
        verifyNoInteractions(snapshots);
    }

    @Test
    void missingLegacySnapshotFailsClosed() {
        when(snapshots.findById(3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.load(7L, 3L)).isInstanceOf(MolelawException.class);
    }

    @Test
    void alteredSnapshotFailsIntegrityCheck() {
        when(snapshots.findById(3L)).thenReturn(Optional.of(new ChatEvidenceSnapshot(room, "[]", "bad")));
        assertThatThrownBy(() -> service.load(7L, 3L)).isInstanceOf(MolelawException.class);
    }

    @Test
    void refusesOverwriteAndDuplicateIds() {
        EvidenceItem item = evidence();
        assertThatThrownBy(() -> service.freeze(room, List.of(item, item))).isInstanceOf(MolelawException.class);
        verifyNoInteractions(snapshots);
        when(snapshots.existsById(3L)).thenReturn(true);
        assertThatThrownBy(() -> service.freeze(room, List.of(item))).isInstanceOf(MolelawException.class);
        verify(snapshots, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsUnknownKindAndOversizedPayload() {
        EvidenceItem invalid = new EvidenceItem(UUID.randomUUID().toString(), "ANSWER", "s", "title", "https://law.go.kr", "text");
        assertThatThrownBy(() -> service.freeze(room, List.of(invalid))).isInstanceOf(MolelawException.class);
        EvidenceItem oversized = new EvidenceItem(UUID.randomUUID().toString(), "LAW_CHUNK", "s", "title", "https://law.go.kr", "a".repeat(2 * 1024 * 1024));
        assertThatThrownBy(() -> service.freeze(room, List.of(oversized))).isInstanceOf(MolelawException.class);
        verify(snapshots, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    private static EvidenceItem evidence() {
        return new EvidenceItem(UUID.randomUUID().toString(), "LAW_CHUNK", "law-1/article-1",
                "민법 제1조", "https://www.law.go.kr/법령/민법", "민사에 관하여 법률에 규정이 없으면 관습법에 의한다.");
    }
}
