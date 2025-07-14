package com.MoleLaw_backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "law")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Law {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;  // 예: 도로교통법

    @Column(name = "law_code")
    private String lawCode;  // API에서 제공하는 코드

    private String department;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "law", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LawChunk> chunks;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
