package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.Law;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LawRepository extends JpaRepository<Law, Long> {

    Optional<Law> findByName(String name);

    boolean existsByName(String name);

    Optional<Law> findByNameAndLawCode(String lawName, String lawCode);
}
