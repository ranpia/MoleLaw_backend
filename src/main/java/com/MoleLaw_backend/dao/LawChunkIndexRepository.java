package com.MoleLaw_backend.dao;

import com.MoleLaw_backend.entity.LawChunkIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LawChunkIndexRepository extends JpaRepository<LawChunkIndex, String> {
    List<LawChunkIndex> findByGeneration(String generation);
}
