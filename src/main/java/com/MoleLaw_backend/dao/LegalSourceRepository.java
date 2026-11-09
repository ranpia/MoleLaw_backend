package com.MoleLaw_backend.dao;

import com.MoleLaw_backend.entity.LegalSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalSourceRepository extends JpaRepository<LegalSource, String> {
}
