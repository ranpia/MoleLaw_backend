package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.LawChunkIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LawChunkIndexRepository extends JpaRepository<LawChunkIndex, String> {
    List<LawChunkIndex> findByGeneration(String generation);
}
