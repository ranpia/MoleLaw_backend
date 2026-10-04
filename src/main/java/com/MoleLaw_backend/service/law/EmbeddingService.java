package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.GptApiException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {
    private final EmbeddingModel embeddingModel;

    @Getter
    @Value("${spring.ai.openai.embedding.options.model}")
    private String modelName;

    @Value("${spring.ai.openai.embedding.options.dimensions}")
    private int dimensions;

    public float[] generateEmbedding(String content) {
        try {
            float[] vector = embeddingModel.embed(content);
            if (vector == null || vector.length != dimensions) {
                throw new GptApiException(ErrorCode.GPT_API_FAILURE, "임베딩 차원 불일치");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new GptApiException(ErrorCode.GPT_API_FAILURE, "유효하지 않은 임베딩 값");
                }
            }
            return vector;
        } catch (GptApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("임베딩 생성 실패: errorType={}", e.getClass().getSimpleName());
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
