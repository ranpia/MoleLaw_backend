package com.MoleLaw_backend.domain.repository;

import com.MoleLaw_backend.domain.entity.Law;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LawRepository extends JpaRepository<Law, Long> {

    Optional<Law> findByName(String name);

    boolean existsByName(String name);
}
