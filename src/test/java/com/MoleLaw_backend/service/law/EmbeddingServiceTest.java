package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.exception.GptApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingServiceTest {
    private EmbeddingModel model;
    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        model = mock(EmbeddingModel.class);
        service = new EmbeddingService(model);
        ReflectionTestUtils.setField(service, "dimensions", 3);
    }

    @Test
    void returnsModelVectorWithoutRepeatedEmbedding() {
        float[] vector = {1, 0, 0};
        when(model.embed("question")).thenReturn(vector);
        assertSame(vector, service.generateEmbedding("question"));
        verify(model).embed("question");
    }

    @Test
    void rejectsVectorWithUnexpectedDimensions() {
        when(model.embed("question")).thenReturn(new float[]{1, 0});
        assertThrows(GptApiException.class, () -> service.generateEmbedding("question"));
    }

    @Test
    void rejectsNonfiniteVectorValues() {
        when(model.embed("question")).thenReturn(new float[]{Float.NaN, 0, 1});
        assertThrows(GptApiException.class, () -> service.generateEmbedding("question"));
    }
}
