package com.MoleLaw_backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "law_embedding")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LawEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "law_chunk_id", nullable = false)
    private LawChunk lawChunk;

    @Lob
    @Column(name = "embedding_vector", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] embeddingVector;  // float[] 직렬화된 형태

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
