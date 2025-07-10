package com.MoleLaw_backend.domain.repository;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.domain.entity.LawChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LawChunkRepository extends JpaRepository<LawChunk, Long> {

    List<LawChunk> findByLaw(Law law);

    List<LawChunk> findByLawId(Long lawId);

    List<LawChunk> findByLawIdOrderByArticleNumberAscClauseNumberAscChunkIndexAsc(Long lawId);

    Optional<LawChunk> findByLawAndArticleNumberAndClauseNumber(Law law, String articleNo, String clauseNo);
}
