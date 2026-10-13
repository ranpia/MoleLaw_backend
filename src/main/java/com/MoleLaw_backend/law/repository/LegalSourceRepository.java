package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.LegalSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalSourceRepository extends JpaRepository<LegalSource, String> {
}
