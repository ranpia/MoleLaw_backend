package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.auth.security.JwtUtil;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestAccessTokenStoreConfig.class)
class UserIdAuthenticationTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtUtil jwt;
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void changingEmailDoesNotChangeTheIdentityOfExistingAccessAndRefresh() throws Exception {
        var account = signup("id-email-before@example.com");
        Long userId = jwt.getUserIdFromAccessToken(account.access().getValue());
        jdbc.update("UPDATE user SET email = ? WHERE id = ?", "id-email-after@example.com", userId);
        mvc.perform(get("/api/auth/me").cookie(account.access())).andExpect(status().isOk())
                .andExpect(jsonPath("data.email").value("id-email-after@example.com"));
        var reissue = mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(account.refresh()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(jwt.getUserIdFromAccessToken(reissue.getCookie("accessToken").getValue())).isEqualTo(userId);
    }

    @Test
    void signupLoginAndReissueUseTheSameDatabaseIdForBothTokensAndAccountChanges() throws Exception {
        var signup = signup("id-flow@example.com");
        User user = users.findByEmailAndProvider("id-flow@example.com", "local").orElseThrow();
        assertThat(user.getId()).isPositive();
        assertThat(jwt.getUserIdFromAccessToken(signup.access().getValue())).isEqualTo(user.getId());
        assertThat(jwt.getUserIdFromRefreshToken(signup.refresh().getValue())).isEqualTo(user.getId());
        var login = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"id-flow@example.com\",\"password\":\"Example-password-123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var loginJson = mapper.readTree(login.getContentAsString());
        assertThat(jwt.getUserIdFromAccessToken(loginJson.path("accessToken").asText())).isEqualTo(user.getId());
        assertThat(jwt.getUserIdFromRefreshToken(loginJson.path("refreshToken").asText())).isEqualTo(user.getId());
        var reissue = mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(signup.refresh()))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie access = reissue.getCookie("accessToken");
        assertThat(jwt.getUserIdFromAccessToken(access.getValue())).isEqualTo(user.getId());
        mvc.perform(patch("/api/auth/nickname").with(csrf()).cookie(access).contentType("application/json")
                        .content("{\"newNickname\":\"ID로 변경\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isOk())
                .andExpect(jsonPath("data.email").value("id-flow@example.com"))
                .andExpect(jsonPath("data.nickname").value("ID로 변경"));
        mvc.perform(patch("/api/auth/password").with(csrf()).cookie(access).contentType("application/json")
                        .content("{\"newPassword\":\"Changed-password-123!\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"id-flow@example.com\",\"password\":\"Changed-password-123!\"}"))
                .andExpect(status().isOk());
        verifyNoInteractions(chatModel, embeddingModel);
    }

    @Test
    void usersCannotReadOrDeleteAnotherUsersRoom() throws Exception {
        var owner = signup("id-owner@example.com");
        var outsider = signup("id-outsider@example.com");
        User user = users.findById(jwt.getUserIdFromAccessToken(owner.access().getValue())).orElseThrow();
        ChatRoom room = rooms.save(ChatRoom.builder().user(user).title("ID 소유권 확인").build());
        mvc.perform(get("/api/chat-rooms/{id}", room.getId()).cookie(owner.access()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat-rooms/{id}", room.getId()).cookie(outsider.access()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("error.code").value("UNAUTHORIZED_CHATROOM_ACCESS"));
        mvc.perform(delete("/api/chat-rooms/{id}", room.getId()).with(csrf()).cookie(outsider.access()))
                .andExpect(status().isForbidden());
        assertThat(rooms.existsById(room.getId())).isTrue();
        mvc.perform(delete("/api/auth/me").with(csrf()).cookie(owner.access()))
                .andExpect(status().isNoContent());
        assertThat(rooms.existsById(room.getId())).isFalse();
        mvc.perform(get("/api/auth/me").cookie(owner.access())).andExpect(status().isUnauthorized());
        verifyNoInteractions(chatModel, embeddingModel);
    }

    @Test
    void deletedUsersTokensCannotAuthenticateAnAccountRecreatedWithTheSameEmail() throws Exception {
        var old = signup("id-recreated@example.com");
        Long oldId = jwt.getUserIdFromAccessToken(old.access().getValue());
        mvc.perform(delete("/api/auth/me").with(csrf()).cookie(old.access())).andExpect(status().isNoContent());
        var replacement = signup("id-recreated@example.com");
        Long newId = jwt.getUserIdFromAccessToken(replacement.access().getValue());
        assertThat(newId).isNotEqualTo(oldId);
        mvc.perform(get("/api/auth/me").cookie(old.access())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(old.refresh()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("error.code").value("TOKEN_FAIL"));
        mvc.perform(get("/api/auth/me").cookie(replacement.access())).andExpect(status().isOk());
    }

    private Tokens signup(String email) throws Exception {
        var response = mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("email", email,
                                "password", "Example-password-123!", "nickname", "ID 검사"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie refresh = java.util.Arrays.stream(response.getCookies())
                .filter(cookie -> cookie.getName().equals("refreshToken") && cookie.getPath().equals("/api/auth"))
                .findFirst().orElseThrow();
        return new Tokens(response.getCookie("accessToken"), refresh);
    }

    private record Tokens(Cookie access, Cookie refresh) {}
}
