package com.MoleLaw_backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "law_chunk")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LawChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "law_id", nullable = false)
    private Law law;

    @Column(name = "article_number")
    private String articleNumber;  // 예: "148조의2"

    @Column(name = "clause_number")
    private String clauseNumber;  // 예: "제1항"

    @Column(name = "chunk_index")
    private Integer chunkIndex;  // 한 조문 내 여러 chunk일 경우

    @Column(name = "content_text", columnDefinition = "TEXT")
    private String contentText;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToOne(mappedBy = "lawChunk", cascade = CascadeType.ALL, orphanRemoval = true)
    private LawEmbedding embedding;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
