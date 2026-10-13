package com.MoleLaw_backend.domain.repository;

import com.MoleLaw_backend.domain.entity.LegalSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalSourceRepository extends JpaRepository<LegalSource, String> {
}
