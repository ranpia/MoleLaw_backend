package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.LawChunk;
import com.MoleLaw_backend.law.entity.LawEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LawEmbeddingRepository extends JpaRepository<LawEmbedding, Long> {

    Optional<LawEmbedding> findByLawChunk(LawChunk lawChunk);

    boolean existsByLawChunk(LawChunk lawChunk);

    List<LawEmbedding> findAll();
}
