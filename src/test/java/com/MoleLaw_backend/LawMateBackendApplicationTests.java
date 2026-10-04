package com.MoleLaw_backend;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class LawMateBackendApplicationTests {

    @MockitoBean
    private ChatModel chatModel;
    @MockitoBean
    private EmbeddingModel embeddingModel;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ChatClient chatClient;

    @Test
    void contextLoadsWithoutExternalDatabaseOrModelCalls() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        assertNotNull(chatClient);
        verify(chatModel, never()).call(any(Prompt.class));
        verify(chatModel, never()).stream(any(Prompt.class));
        verifyNoInteractions(embeddingModel);
    }
}

