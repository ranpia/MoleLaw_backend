package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.LegalSourceIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LegalSourceIndexRepository extends JpaRepository<LegalSourceIndex, String> {
    List<LegalSourceIndex> findByConfigFingerprintAndStatusOrderByCompletedAtDescGenerationDesc(String fingerprint, String status);
}
