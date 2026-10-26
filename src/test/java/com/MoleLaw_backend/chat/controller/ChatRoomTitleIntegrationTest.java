package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.common.config.TestRefreshTokenStoreConfig;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRefreshTokenStoreConfig.class)
@Transactional
class ChatRoomTitleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired ObjectMapper mapper;
    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel embeddings;

    @Test void ownerCanRenameWithoutChangingCreationTimeOrInvokingAi() throws Exception {
        var owner = account("rename-owner@example.test");
        var room = rooms.saveAndFlush(ChatRoom.builder().user(owner).title("기존 제목").build());
        var created = room.getCreatedAt();
        String path = "/api/chat-rooms/" + room.getId() + "/title";
        mvc.perform(patch(path).with(user(new CustomUserDetails(owner))).with(csrf()).contentType("application/json")
                .content("{\"title\":\"  변경한 상담 제목  \"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(rooms.findById(room.getId()).orElseThrow().getTitle()).isEqualTo("변경한 상담 제목");
        assertThat(room.getCreatedAt()).isEqualTo(created);
        mvc.perform(get("/api/chat-rooms").with(user(new CustomUserDetails(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("변경한 상담 제목"));
        verifyNoInteractions(model, embeddings);
    }

    @Test void invalidTitlesForeignRoomsMissingRoomsAndMissingCsrfAreRejected() throws Exception {
        var owner = account("rename-validation@example.test");
        var other = account("rename-other@example.test");
        var room = rooms.saveAndFlush(ChatRoom.builder().user(owner).title("유지할 제목").build());
        String path = "/api/chat-rooms/" + room.getId() + "/title";
        for (String title : List.of("", "  ", "가".repeat(256), "😀".repeat(256)))
            mvc.perform(patch(path).with(user(new CustomUserDetails(owner))).with(csrf()).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("title", title)))).andExpect(status().isBadRequest());
        mvc.perform(patch(path).with(user(new CustomUserDetails(owner))).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(path).with(user(new CustomUserDetails(other))).with(csrf()).contentType("application/json").content("{\"title\":\"변경\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/chat-rooms/9223372036854775807/title").with(user(new CustomUserDetails(owner))).with(csrf())
                .contentType("application/json").content("{\"title\":\"변경\"}")).andExpect(status().isNotFound());
        mvc.perform(patch(path).with(user(new CustomUserDetails(owner))).contentType("application/json").content("{\"title\":\"변경\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path).with(csrf()).contentType("application/json").content("{\"title\":\"변경\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(room.getTitle()).isEqualTo("유지할 제목");
    }

    private User account(String email) {
        return users.saveAndFlush(User.builder().email(email).nickname("제목 테스트").provider("local").password("test-hash").build());
    }
}
