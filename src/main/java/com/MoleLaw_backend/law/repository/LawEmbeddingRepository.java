package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.LawChunk;
import com.MoleLaw_backend.law.entity.LawEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LawEmbeddingRepository extends JpaRepository<LawEmbedding, Long> {

    boolean existsByLawChunk(LawChunk lawChunk);

}
