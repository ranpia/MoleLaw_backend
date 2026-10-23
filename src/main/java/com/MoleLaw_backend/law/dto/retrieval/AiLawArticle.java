package com.MoleLaw_backend.law.dto.retrieval;

public record AiLawArticle(String articleId, String lawId, String mst, String lawName,
                           String effectiveDate, String articleNumber, String articleBranchNumber,
                           String articleTitle, String content) { }
