package com.MoleLaw_backend.domain.repository;

import com.MoleLaw_backend.domain.entity.Law;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LawRepository extends JpaRepository<Law, Long> {
}
