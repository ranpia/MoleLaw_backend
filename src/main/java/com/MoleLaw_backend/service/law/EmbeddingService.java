package com.MoleLaw_backend.service.law;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    @Value("${openai.api-key}")
    private String openaiKey;

    private final WebClient webClient = WebClient.create("https://api.openai.com");

    private static final String EMBEDDING_MODEL = "text-embedding-3-small";

    public float[] generateEmbedding(String content) {
        try {
            Map<String, Object> request = Map.of(
                    "input", content,
                    "model", EMBEDDING_MODEL
            );

            JsonNode response = webClient.post()
                    .uri("/v1/embeddings")
                    .header("Authorization", "Bearer " + openaiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            // ✅ 응답 구조 검사
            if (response == null || !response.has("data")) {
                throw new RuntimeException("OpenAI 응답 누락: " + response);
            }

            JsonNode embeddingArray = response.path("data").get(0).path("embedding");

            float[] result = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                result[i] = (float) embeddingArray.get(i).asDouble();
            }
            return result;

        } catch (WebClientResponseException e) {
            log.error("❌ OpenAI WebClient 오류: {}", e.getResponseBodyAsString());
            throw new RuntimeException("OpenAI 임베딩 요청 실패", e);
        } catch (Exception e) {
            log.error("❌ 임베딩 예외: {}", e.getMessage(), e);
            throw new RuntimeException("임베딩 생성 중 오류", e);
        }
    }
}
