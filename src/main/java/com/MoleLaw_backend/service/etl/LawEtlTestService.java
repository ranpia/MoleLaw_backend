package com.MoleLaw_backend.service.etl;

import com.MoleLaw_backend.dto.request.LawEtlTestRequest;
import com.MoleLaw_backend.dto.request.LawSearchFilters;
import com.MoleLaw_backend.dto.response.LawEtlTestResponse;
import com.MoleLaw_backend.dto.tool.LegalToolContracts.SearchRequest;
import com.MoleLaw_backend.dto.vector.LawIndexResult;
import com.MoleLaw_backend.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.util.exception.LegalToolException;
import com.MoleLaw_backend.service.agents.tool.LegalApiToolService;
import com.MoleLaw_backend.service.agents.tool.LegalToolContext;
import com.MoleLaw_backend.service.agents.tool.LawVectorizationToolService;
import com.MoleLaw_backend.service.agents.tool.LawRagSearchToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("local & !test")
@ConditionalOnProperty(prefix = "app.law-vector", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class LawEtlTestService {
    private final LegalApiToolService api;
    private final LawVectorizationToolService vectorization;
    private final LawRagSearchToolService retrieval;

    public LawEtlTestResponse collect(LawEtlTestRequest request) {
        var context = context();
        var filters = request.filters() == null
                ? new LawSearchFilters(null, null, null, true) : request.filters();
        List<LawIndexResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pages = 0;
        Integer next = request.startPage();
        for (int page = request.startPage(); pages < request.maxPages(); page++) {
            var found = api.searchLaws(context, new SearchRequest(request.query(), 1, null, page, 1, filters));
            if (found.status() == com.MoleLaw_backend.dto.tool.LegalToolContracts.Status.BUDGET_EXHAUSTED) {
                throw new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED, false);
            }
            pages++;
            for (var candidate : found.candidates()) {
                if (!seen.add(candidate.id())) continue;
                var detail = api.getLawDetail(context, candidate.id());
                if (detail.contentAvailable()) results.add(vectorization.vectorizeLaw(context, detail.referenceId()));
            }
            next = found.hasNext() && page < 10000 ? page + 1 : null;
            if (next == null) break;
        }
        return new LawEtlTestResponse(context.executionId(), pages, next, results);
    }

    public List<LawSearchEvidence> search(String query) {
        com.MoleLaw_backend.service.ChatContentLimits.question(query);
        return retrieval.searchTop15(context(), query);
    }

    public LawIndexResult reindex(String referenceId) {
        return vectorization.reindexStoredLaw(context(), referenceId);
    }

    private LegalToolContext context() {
        Clock clock = Clock.systemUTC();
        return new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION, UUID.randomUUID().toString(),
                clock.instant().plus(Duration.ofMinutes(15)), 2048, clock);
    }

}
