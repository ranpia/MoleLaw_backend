package com.MoleLaw_backend.service.law;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmbeddingService {

    @Value("${openai.api-key}")
    private String openaiKey;

    private final WebClient webClient = WebClient.create("https://api.openai.com");

    public float[] generateEmbedding(String content) {
        String model = "text-embedding-3-small";

        Map<String, Object> request = Map.of(
                "input", content,
                "model", model
        );

        JsonNode response = webClient.post()
                .uri("/v1/embeddings")
                .header("Authorization", "Bearer " + openaiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        JsonNode embeddingArray = response
                .get("data").get(0)
                .get("embedding");

        float[] result = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            result[i] = (float) embeddingArray.get(i).asDouble();
        }
        return result;
    }

}

