package com.MoleLaw_backend.chat.service.evidence;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.repository.ChatEvidenceSnapshotRepository;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.service.ChatTurnService;
import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.chat.dto.answer.PreparedLegalAnswer;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:room-evidence-test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ChatEvidenceService.class, ChatTurnService.class, RoomEvidenceIntegrationTest.JsonConfiguration.class})
class RoomEvidenceIntegrationTest {
    @Autowired private ChatEvidenceService service;
    @Autowired private ChatEvidenceSnapshotRepository snapshots;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ChatTurnService initialTurns;
    @Autowired private MessageRepository messages;

    @Test
    void restoresFrozenContentAfterPersistenceContextClear() {
        ChatRoom room = room();
        List<EvidenceItem> original = evidence();
        service.freeze(room, original);
        entityManager.clear();

        assertThat(service.load(room.getUser().getId(), room.getId())).isEqualTo(original);
        assertThatThrownBy(() -> service.load(room.getUser().getId(), room.getId()).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void secondFreezeCannotReplaceOriginalEvidence() {
        ChatRoom room = room();
        service.freeze(room, evidence());

        assertThatThrownBy(() -> service.freeze(room, evidence())).isInstanceOf(MolelawException.class);
        assertThat(snapshots.findById(room.getId()).orElseThrow().getEvidenceJson())
                .contains("original statute body");
    }

    @Test
    void differentOwnerCannotReadSnapshot() {
        ChatRoom room = room();
        service.freeze(room, evidence());

        assertThatThrownBy(() -> service.load(room.getUser().getId() + 1, room.getId()))
                .isInstanceOf(MolelawException.class);
    }

    @Test
    void changedStoredContentFailsHashVerification() {
        ChatRoom room = room();
        service.freeze(room, evidence());
        entityManager.createNativeQuery("update chat_evidence_snapshot set evidence_json = :json where room_id = :id")
                .setParameter("json", "[]").setParameter("id", room.getId()).executeUpdate();
        entityManager.clear();

        assertThatThrownBy(() -> service.load(room.getUser().getId(), room.getId()))
                .isInstanceOf(MolelawException.class);
    }

    @Test
    void snapshotAndRoomCanBeDeletedTogetherWithoutRemovingOwner() {
        ChatRoom room = room();
        Long ownerId = room.getUser().getId();
        service.freeze(room, evidence());

        entityManager.clear();
        rooms.deleteById(room.getId());
        rooms.flush();
        entityManager.clear();

        assertThat(snapshots.findById(room.getId())).isEmpty();
        assertThat(rooms.findById(room.getId())).isEmpty();
        assertThat(entityManager.find(User.class, ownerId)).isNotNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedInitialTurnRollsBackRoomAndSnapshotTogether() {
        AtomicReference<Long> roomId = new AtomicReference<>();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ChatRoom room = room();
            roomId.set(room.getId());
            service.freeze(room, evidence());
            throw new IllegalStateException("initial turn persistence failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(roomId.get()).isNotNull();
        assertThat(rooms.findById(roomId.get())).isEmpty();
        assertThat(snapshots.findById(roomId.get())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void initialPersistenceRejectsInvalidEvidenceAndRollsBackNewRoom() {
        User user = new TransactionTemplate(transactions).execute(status -> {
            User owner = User.builder().email("rollback@example.test").provider("local")
                    .nickname("rollback").password("test-password-hash").build();
            entityManager.persist(owner);
            return owner;
        });
        long roomCount = rooms.count();
        long snapshotCount = snapshots.count();
        long messageCount = messages.count();
        try {
            assertThatThrownBy(() -> initialTurns.saveInitialTurn(user, "failed", "question",
                    new PreparedLegalAnswer(new LegalAnswerResponse("answer", "info"), List.of())))
                    .isInstanceOf(MolelawException.class);
            assertThat(rooms.count()).isEqualTo(roomCount);
            assertThat(snapshots.count()).isEqualTo(snapshotCount);
            assertThat(messages.count()).isEqualTo(messageCount);
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    entityManager.remove(entityManager.find(User.class, user.getId())));
        }
    }

    @Test
    void initialPersistenceSavesSnapshotAndOnlyTwoEncryptedMessages() {
        ChatRoom seed = room();
        ChatRoom created = initialTurns.saveInitialTurn(seed.getUser(), "new room", "first question",
                new PreparedLegalAnswer(new LegalAnswerResponse("cited answer", "source info"), evidence()));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.load(seed.getUser().getId(), created.getId())).isEqualTo(evidence());
        var storedMessages = messages.findByChatRoomIdOrderByTimestampAscIdAsc(created.getId());
        assertThat(storedMessages).hasSize(2);
        assertThat(storedMessages).extracting(message -> EncryptUtil.decrypt(message.getContent()))
                .containsExactly("first question", "cited answer");
        assertThat(storedMessages).extracting(com.MoleLaw_backend.chat.entity.Message::getContent)
                .doesNotContain("first question", "cited answer", "source info");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedFollowUpRollsBackAlreadyInsertedQuestion() {
        ChatRoom room = new TransactionTemplate(transactions).execute(status -> room());
        long messageCount = messages.count();
        try {
            // Validation rejects the pair before either message is inserted.
            assertThatThrownBy(() -> initialTurns.saveFollowUpTurn(room, "new question", null))
                    .isInstanceOf(RuntimeException.class);
            assertThat(messages.findByChatRoomIdOrderByTimestampAscIdAsc(room.getId())).isEmpty();
            assertThat(messages.count()).isEqualTo(messageCount);
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                rooms.deleteById(room.getId());
                rooms.flush();
                entityManager.remove(entityManager.find(User.class, room.getUser().getId()));
            });
        }
    }

    @Test
    void successfulFollowUpSavesBothEncryptedMessages() {
        ChatRoom room = room();
        initialTurns.saveFollowUpTurn(room, "new question", "new answer");
        entityManager.flush();
        entityManager.clear();
        var stored = messages.findByChatRoomIdOrderByTimestampAscIdAsc(room.getId());
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(message -> EncryptUtil.decrypt(message.getContent()))
                .containsExactly("new question", "new answer");
        assertThat(stored).extracting(com.MoleLaw_backend.chat.entity.Message::getSender)
                .containsExactly(com.MoleLaw_backend.chat.entity.Message.Sender.USER,
                        com.MoleLaw_backend.chat.entity.Message.Sender.BOT);
    }

    @Test
    void deletingOwnerCascadesRoomsMessagesAndEvidenceWithoutInverseSnapshotMapping() {
        ChatRoom room = room();
        Long roomId = room.getId();
        Long ownerId = room.getUser().getId();
        service.freeze(room, evidence());
        initialTurns.saveFollowUpTurn(room, "질문", "답변");
        entityManager.flush();
        entityManager.clear();
        entityManager.remove(entityManager.find(User.class, ownerId));
        entityManager.flush();
        entityManager.clear();
        assertThat(rooms.findById(roomId)).isEmpty();
        assertThat(snapshots.findById(roomId)).isEmpty();
        assertThat(messages.countByChatRoomId(roomId)).isZero();
    }

    @Test
    void encryptedLongtextOverOldTextLimitSurvivesRoundtrip() {
        ChatRoom room = room();
        String answer = "한글 답변😀".repeat(20000);
        initialTurns.saveFollowUpTurn(room, "질문", answer);
        entityManager.flush();
        entityManager.clear();
        var stored = messages.findByChatRoomIdOrderByTimestampAscIdAsc(room.getId());
        assertThat(stored.get(1).getContent().length()).isGreaterThan(65535);
        assertThat(EncryptUtil.decrypt(stored.get(1).getContent())).isEqualTo(answer);
    }

    @Test
    void timestampTiesUseMessageIdAndRoomIdAsStableOrderingKeys() {
        ChatRoom first = room();
        ChatRoom second = rooms.saveAndFlush(ChatRoom.builder().user(first.getUser()).title("second").build());
        initialTurns.saveFollowUpTurn(first, "first question", "first answer");
        entityManager.flush();
        entityManager.createNativeQuery("update message set timestamp = '2026-10-21 00:00:00' where chat_room_id = :room")
                .setParameter("room", first.getId()).executeUpdate();
        entityManager.createNativeQuery("update chat_room set created_at = '2026-10-21 00:00:00' where user_id = :owner")
                .setParameter("owner", first.getUser().getId()).executeUpdate();
        entityManager.clear();

        var history = messages.findByChatRoomIdOrderByTimestampAscIdAsc(first.getId());
        assertThat(history).extracting(value -> value.getId()).isSorted();
        assertThat(messages.findFirstByChatRoomIdOrderByTimestampAscIdAsc(first.getId()).orElseThrow().getId())
                .isEqualTo(history.get(0).getId());
        assertThat(rooms.findByUserOrderByCreatedAtDescIdDesc(first.getUser(),
                org.springframework.data.domain.PageRequest.of(0, 1))).extracting(value -> value.getId())
                .containsExactly(second.getId());
        assertThat(rooms.findByUserOrderByCreatedAtDescIdDesc(first.getUser(),
                org.springframework.data.domain.PageRequest.of(1, 1))).extracting(value -> value.getId())
                .containsExactly(first.getId());
    }

    private ChatRoom room() {
        User owner = User.builder().email("owner@example.test").provider("local")
                .nickname("owner").password("test-password-hash").build();
        entityManager.persist(owner);
        return rooms.saveAndFlush(ChatRoom.builder().user(owner).title("consultation").build());
    }

    private List<EvidenceItem> evidence() {
        return List.of(new EvidenceItem("00000000-0000-0000-0000-000000000001", "LAW_CHUNK",
                "law-1:article-1", "Civil law article", "https://www.law.go.kr/law/1",
                "original statute body ".repeat(10000)));
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean ObjectMapper evidenceObjectMapper() { return new ObjectMapper(); }
    }
}
