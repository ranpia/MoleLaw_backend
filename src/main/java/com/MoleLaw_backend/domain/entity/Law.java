package com.MoleLaw_backend.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Law {
    @Id
    private Long id;

    private String title;
    private String content;
}
