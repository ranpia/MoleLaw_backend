package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.LegalSourceIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LegalSourceIndexRepository extends JpaRepository<LegalSourceIndex, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from LegalSourceIndex i where i.generation = :generation")
    java.util.Optional<LegalSourceIndex> lockGeneration(@org.springframework.data.repository.query.Param("generation") String generation);
    List<LegalSourceIndex> findByConfigFingerprintAndStatusOrderByCompletedAtDescGenerationDesc(String fingerprint, String status);
}
