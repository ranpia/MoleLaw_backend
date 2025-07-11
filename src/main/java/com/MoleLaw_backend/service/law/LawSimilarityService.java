package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.domain.entity.LawEmbedding;
import com.MoleLaw_backend.domain.repository.LawEmbeddingRepository;
import com.MoleLaw_backend.domain.repository.LawRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LawSimilarityService {

    private final LawEmbeddingRepository lawEmbeddingRepository;
    private final EmbeddingService embeddingService;
    private final ExtractKeyword extractKeyword;
    private final LawSearchService lawSearchService;
    private final LawEmbeddingService lawEmbeddingService;

    public List<LawChunk> findSimilarChunks(String question, int topK) {
        float[] queryVec = embeddingService.generateEmbedding(question);

        List<LawEmbedding> all = lawEmbeddingRepository.findAll();
        List<ScoredChunk> scored = new ArrayList<>();

        for (LawEmbedding embedding : all) {
            float[] chunkVec = deserializeFloatArray(embedding.getEmbeddingVector());
            double similarity = cosineSimilarity(queryVec, chunkVec);
            scored.add(new ScoredChunk(embedding.getLawChunk(), similarity));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::similarity).reversed())
                .limit(topK)
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());
    }

    public List<LawChunk> findSimilarChunksWithFallback(String question, int topK) {
        List<LawChunk> chunks = findSimilarChunks(question, topK);

        double topScore = cosineSimilarity(
                embeddingService.generateEmbedding(question),
                deserializeFloatArray(chunks.get(0).getEmbedding().getEmbeddingVector())
        );

        if (topScore < 0.75) {
            System.out.println("📉 유사도 낮음: {"+topScore+"} → fallback 발동");

            // 1. GPT 또는 키워드 추출기로 법령명 추정
            List<String> lawNames = extractKeyword.extractKeywords(question).getKeywords(); // 예: ["도로교통법"]

            // 2. 법령 수집 + chunk 저장 + 임베딩
            for (String lawName : lawNames) {
                List<Law> saved = lawSearchService.saveLawsWithArticles(lawName);
                lawEmbeddingService.embedLaws(saved);
            }

            // 3. 다시 재탐색
            return findSimilarChunks(question, topK);
        }

        return chunks;
    }


    private float[] deserializeFloatArray(byte[] bytes) {
        try (var bais = new java.io.ByteArrayInputStream(bytes);
             var dis = new java.io.DataInputStream(bais)) {
            int len = bytes.length / 4;
            float[] result = new float[len];
            for (int i = 0; i < len; i++) {
                result[i] = dis.readFloat();
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("역직렬화 실패", e);
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record ScoredChunk(LawChunk chunk, double similarity) {}
}
