package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.domain.entity.LawEmbedding;
import com.MoleLaw_backend.domain.repository.LawChunkRepository;
import com.MoleLaw_backend.domain.repository.LawEmbeddingRepository;
import com.MoleLaw_backend.domain.repository.LawRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class LawEmbeddingService {

    private final LawChunkRepository lawChunkRepository;
    private final EmbeddingService embeddingService;
    private final LawEmbeddingRepository lawEmbeddingRepository;

    private static final String currentModel = "text-embedding-3-small";

    public void embedLaws(List<Law> lawList) {
        ExecutorService executor = Executors.newFixedThreadPool(5); // 스레드 수 조절 가능
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Law law : lawList) {
            List<LawChunk> chunks = lawChunkRepository.findByLawId(law.getId());

            for (LawChunk chunk : chunks) {
                // 이미 임베딩된 경우 skip
                if (chunk.getEmbedding() != null &&
                        chunk.getEmbedding().getModelName().equals(currentModel)) {
                    continue;
                }

                // 병렬 처리
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        if (lawEmbeddingRepository.existsByLawChunk(chunk)) return;
                        float[] vec = embeddingService.generateEmbedding(chunk.getContentText());
                        byte[] serialized = serializeFloatArray(vec);

                        LawEmbedding embedding = LawEmbedding.builder()
                                .lawChunk(chunk)
                                .embeddingVector(serialized)
                                .modelName(currentModel)
                                .build();

                        chunk.setEmbedding(embedding);
                        lawChunkRepository.save(chunk);
                    } catch (Exception e) {
                        System.err.println("❌ 임베딩 실패 - chunkId={"+
                                chunk.getId()+"}, lawId={"+chunk.getLaw().getId()+"}, message={"+ e.getMessage()+"}" + e);
                    }
                }, executor);

                futures.add(future);
            }
        }

        // 모든 임베딩 완료까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();
        System.out.println("✅ 병렬 임베딩 완료: 총 {}건 처리"+ futures.size());
    }


    private byte[] serializeFloatArray(float[] array) {
        try (var baos = new java.io.ByteArrayOutputStream();
             var dos = new java.io.DataOutputStream(baos)) {
            for (float f : array) dos.writeFloat(f);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("임베딩 직렬화 실패", e);
        }
    }
}
