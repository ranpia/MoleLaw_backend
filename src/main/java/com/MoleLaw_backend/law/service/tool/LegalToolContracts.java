package com.MoleLaw_backend.law.service.tool;

import java.time.Instant;
import java.util.List;
import com.MoleLaw_backend.law.dto.request.LawSearchFilters;

/** Internal server-invoked contracts; never registered on a follow-up ChatClient. */
public final class LegalToolContracts {
    private LegalToolContracts() { }

    public enum Status { AVAILABLE, EMPTY, EXHAUSTED, DUPLICATE, BUDGET_EXHAUSTED }

    public record SearchRequest(String query, int search, String ministry, int page, int display,
                                LawSearchFilters lawFilters) {
        public SearchRequest(String query, int search, String ministry, int page, int display) {
            this(query, search, ministry, page, display, LawSearchFilters.NONE);
        }

        public SearchRequest {
            if (query == null || query.isBlank() || query.length() > 200
                    || (search != 1 && search != 2) || page < 1 || page > 10000
                    || display < 1 || display > 100
                    || (ministry != null && !ministry.matches("[0-9]{1,10}"))) {
                throw new IllegalArgumentException("Invalid legal search conditions");
            }
            query = query.strip();
            lawFilters = lawFilters == null ? LawSearchFilters.NONE : lawFilters;
            if (lawFilters.exactName() && search != 1) {
                throw new IllegalArgumentException("Exact law names require title search");
            }
        }
    }

    public record Candidate(String id, String lawId, String title, String effectiveDate,
                            String caseNumber, String decisionDate, String courtName) { }

    public record SearchResult(Status status, List<Candidate> candidates, int page,
                               int display, int totalCount, boolean hasNext,
                               Instant queriedAt, String sourceUrl) {
        public SearchResult { candidates = List.copyOf(candidates); }
    }

    public record DetailResult(String referenceId, String externalId, String lawId,
                               String effectiveDate, String title, int articleCount,
                               boolean contentAvailable, Instant collectedAt, String sourceUrl,
                               List<String> articleNumbers) {
        public DetailResult { articleNumbers = List.copyOf(articleNumbers); }
    }
}
