package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.Law;
import com.MoleLaw_backend.law.entity.LawChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LawChunkRepository extends JpaRepository<LawChunk, Long> {

    List<LawChunk> findByLawId(Long lawId);

    Optional<LawChunk> findByLawAndArticleNumberAndClauseNumber(Law law, String articleNo, String clauseNo);
}
