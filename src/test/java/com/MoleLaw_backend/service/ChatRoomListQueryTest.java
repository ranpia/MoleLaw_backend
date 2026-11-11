package com.MoleLaw_backend.service;

import com.MoleLaw_backend.entity.ChatRoom;
import com.MoleLaw_backend.entity.Message;
import com.MoleLaw_backend.dao.ChatRoomRepository;
import com.MoleLaw_backend.dao.MessageRepository;
import com.MoleLaw_backend.service.agents.answer.EvidenceRenderer;
import com.MoleLaw_backend.service.agents.answer.LegalAnswerService;
import com.MoleLaw_backend.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.util.TestEncryption;
import com.MoleLaw_backend.util.exception.MolelawException;
import com.MoleLaw_backend.service.agents.analysis.KeywordExtractionService;
import com.MoleLaw_backend.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:room-list-query;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ChatRoomListQueryTest {
    @Autowired private EntityManager entityManager;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private MessageRepository messages;
    private ChatService service;
    private Statistics statistics;

    @BeforeEach
    void prepareService() {
        service = new ChatService(rooms, messages, mock(LegalAnswerService.class), mock(ChatTurnService.class),
                mock(KeywordExtractionService.class), mock(ChatEvidenceService.class), new EvidenceRenderer(),
                TestEncryption.create());
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void usesTwoSelectsForAnyPageSizeAndPreservesOwnerOrderAndDecryptedPreview(int pageSize) {
        var owner = owner("owner@example.test");
        var foreign = owner("foreign@example.test");
        List<Long> expectedIds = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            var room = room(owner, "상담 " + i);
            expectedIds.add(room.getId());
            message(room, "첫 질문😀 " + i);
            message(room, "후속 질문 " + i);
        }
        message(room(foreign, "외부 상담"), "노출 금지");
        entityManager.flush();
        // Deliberately tie timestamps so both queries must retain their ID tie-breaker.
        entityManager.createNativeQuery("update chat_room set created_at = '2026-10-21 00:00:00'").executeUpdate();
        entityManager.createNativeQuery("update message set timestamp = '2026-10-21 00:00:00'").executeUpdate();
        entityManager.clear();
        statistics.clear();

        var result = service.getMyChatRooms(owner, 0, pageSize);

        Collections.reverse(expectedIds);
        assertThat(result).extracting(value -> value.getId())
                .containsExactlyElementsOf(expectedIds.subList(0, Math.min(pageSize, 21)));
        assertThat(result).allSatisfy(value -> assertThat(value.getPreview())
                .isEqualTo("첫 질문😀 " + value.getTitle().substring("상담 ".length())));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void preservesPaginationAndEmptyRoomPreviewWithoutExtraQueries() {
        var owner = owner("owner@example.test");
        var older = room(owner, "이전 방");
        var empty = room(owner, "빈 방");
        message(older, "이전 질문");
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        var first = service.getMyChatRooms(owner, 0, 1);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getId()).isEqualTo(empty.getId());
        assertThat(first.get(0).getPreview()).isEqualTo("(메시지 없음)");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        statistics.clear();
        assertThat(service.getMyChatRooms(owner, 1, 1)).singleElement()
                .satisfies(value -> assertThat(value.getPreview()).isEqualTo("이전 질문"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        statistics.clear();
        assertThat(service.getMyChatRooms(owner, 2, 1)).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void previewUsesTimestampBeforeIdWhenInsertionOrderDiffers() {
        var owner = owner("owner@example.test");
        var room = room(owner, "시간 순서");
        var insertedFirst = message(room, "늦은 시각");
        var insertedLater = message(room, "이른 시각");
        entityManager.flush();
        entityManager.createNativeQuery("update message set timestamp = '2026-10-22 00:00:00' where id = :id")
                .setParameter("id", insertedFirst.getId()).executeUpdate();
        entityManager.createNativeQuery("update message set timestamp = '2026-10-21 00:00:00' where id = :id")
                .setParameter("id", insertedLater.getId()).executeUpdate();
        entityManager.clear();
        statistics.clear();

        assertThat(service.getMyChatRooms(owner)).singleElement()
                .satisfies(value -> assertThat(value.getPreview()).isEqualTo("이른 시각"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void rejectsMissingAuthenticationAndInvalidPaginationBeforeQuerying() {
        statistics.clear();
        assertThatThrownBy(() -> service.getMyChatRooms(null, 0, 20)).isInstanceOf(MolelawException.class);
        assertThatThrownBy(() -> service.getMyChatRooms(User.builder().build(), 0, 20))
                .isInstanceOf(MolelawException.class);
        var owner = User.builder().id(1L).build();
        assertThatThrownBy(() -> service.getMyChatRooms(owner, -1, 20)).isInstanceOf(MolelawException.class);
        assertThatThrownBy(() -> service.getMyChatRooms(owner, 0, 101)).isInstanceOf(MolelawException.class);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    private User owner(String email) {
        var user = User.builder().email(email).provider("local").nickname("조회 시험")
                .password("test-password-hash").build();
        entityManager.persist(user);
        return user;
    }

    private ChatRoom room(User owner, String title) {
        return rooms.save(ChatRoom.builder().user(owner).title(title).build());
    }

    private Message message(ChatRoom room, String text) {
        return messages.save(Message.builder().chatRoom(room).sender(Message.Sender.USER)
                .content(TestEncryption.create().encrypt(text)).build());
    }
}
