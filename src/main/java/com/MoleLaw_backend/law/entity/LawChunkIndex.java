package com.MoleLaw_backend.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Metadata only: immutable snapshot membership and exact embedding-input hash. */
@Getter
@Entity
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(name = "law_chunk_index", indexes = @Index(name = "idx_chunk_generation", columnList = "generation"))
public class LawChunkIndex {
    @Id @Column(length = 36, updatable = false) private String pointId;
    @Column(nullable = false, length = 36, updatable = false) private String generation;
    @Column(nullable = false, length = 64, updatable = false) private String articlePath;
    @Column(nullable = false, updatable = false) private int chunkIndex;
    @Column(nullable = false, length = 64, updatable = false) private String inputHash;

    public LawChunkIndex(String pointId, String generation, String articlePath, int chunkIndex, String inputHash) {
        this.pointId = pointId;
        this.generation = generation;
        this.articlePath = articlePath;
        this.chunkIndex = chunkIndex;
        this.inputHash = inputHash;
    }
}
