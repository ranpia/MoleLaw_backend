package com.MoleLaw_backend.domain.repository;

import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.domain.entity.LawEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LawEmbeddingRepository extends JpaRepository<LawEmbedding, Long> {

    Optional<LawEmbedding> findByLawChunk(LawChunk lawChunk);

    boolean existsByLawChunk(LawChunk lawChunk);
}
