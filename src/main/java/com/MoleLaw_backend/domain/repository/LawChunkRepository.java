package com.MoleLaw_backend.domain.repository;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.domain.entity.LawChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LawChunkRepository extends JpaRepository<LawChunk, Long> {

    List<LawChunk> findByLaw(Law law);

    List<LawChunk> findByLawId(Long lawId);

    List<LawChunk> findByLawIdOrderByArticleNumberAscClauseNumberAscChunkIndexAsc(Long lawId);
}
