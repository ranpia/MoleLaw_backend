package com.MoleLaw_backend.law.dto.retrieval;

import java.util.List;

public record AiLawArticlePage(int totalCount, boolean hasNext, List<AiLawArticle> articles) {
    public AiLawArticlePage { articles = List.copyOf(articles); }
}
