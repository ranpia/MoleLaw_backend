package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.domain.entity.LawEmbedding;
import com.MoleLaw_backend.domain.repository.LawChunkRepository;
import com.MoleLaw_backend.domain.repository.LawEmbeddingRepository;
import com.MoleLaw_backend.util.VectorUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LawEmbeddingService {

    private final EmbeddingService embeddingService;
    private final LawChunkRepository lawChunkRepository;
    private final LawEmbeddingRepository lawEmbeddingRepository;

    public void embedAllChunksForLaw(Long lawId) {
        List<LawChunk> chunks = lawChunkRepository.findByLawId(lawId);

        for (LawChunk chunk : chunks) {
            // 이미 벡터화된 경우 skip
            if (lawEmbeddingRepository.existsByLawChunk(chunk)) continue;

            float[] vector = embeddingService.generateEmbedding(chunk.getContentText());

            LawEmbedding embedding = LawEmbedding.builder()
                    .lawChunk(chunk)
                    .embeddingVector(VectorUtil.toByteArray(vector))
                    .modelName("text-embedding-3-small")
                    .build();

            lawEmbeddingRepository.save(embedding);
        }
    }
}
