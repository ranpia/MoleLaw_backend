package com.MoleLaw_backend.law.dto.tool;

/** aiSearch search=0 (law articles); its search parameter is NOT the legacy TITLE/BODY parameter. */
public record AiLawSearchRequest(String query, int page, int display) {
    public AiLawSearchRequest {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 2000
                || page < 1 || page > 10000 || display < 1 || display > 15)
            throw new IllegalArgumentException("Invalid AI law search request");
        query = query.strip();
    }
}
