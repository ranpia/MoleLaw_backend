package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.response.LawRetrievalComparisonResponse;
import com.MoleLaw_backend.law.dto.response.LawRetrievalComparisonResponse.BackendResult;
import com.MoleLaw_backend.law.dto.response.LawRetrievalComparisonResponse.Hit;
import com.MoleLaw_backend.law.dto.response.LawRetrievalComparisonResponse.Status;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalBackend;
import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.MoleLaw_backend.law.service.tool.LawAiSearchToolService;
import com.MoleLaw_backend.law.service.tool.LawRagSearchToolService;
import com.MoleLaw_backend.law.service.tool.LegalToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Retrieval-only comparison: no LLM, ETL, room creation, or changes to stored evidence. */
@Service
@RequiredArgsConstructor
public class LawRetrievalComparisonService {
    private final LawAiSearchToolService aiSearch;
    private final ObjectProvider<LawRagSearchToolService> qdrant;

    public BackendResult searchAi(AiLawSearchRequest request) {
        long start = System.nanoTime();
        try {
            var result = aiSearch.search(context(), request);
            List<Hit> hits = new ArrayList<>();
            for (var item : result.evidence()) {
                var p = item.provenance();
                hits.add(new Hit(hits.size() + 1, item.title(), p.lawId(), p.mst(), p.effectiveDate(),
                        p.articlePath(), item.content(), item.sourceUrl()));
            }
            return new BackendResult(RetrievalBackend.AI_SEARCH, hits.isEmpty() ? Status.EMPTY : Status.AVAILABLE,
                    elapsed(start), hits.size(), result.totalCount(), result.hasNext(), null, List.copyOf(hits));
        } catch (LegalToolException failure) { return failed(RetrievalBackend.AI_SEARCH, start, failure); }
    }

    public LawRetrievalComparisonResponse compare(String query) {
        AiLawSearchRequest request;
        try { request = new AiLawSearchRequest(query, 1, 15); }
        catch (IllegalArgumentException invalid) { throw new MolelawException(ErrorCode.INVALID_REQUEST); }
        var api = searchAi(request);
        var vector = searchQdrant(request.query());
        var ids = api.hits().stream().map(Hit::lawId).distinct()
                .filter(id -> vector.hits().stream().anyMatch(hit -> id.equals(hit.lawId()))).toList();
        return new LawRetrievalComparisonResponse(request.query(), api, vector, ids);
    }

    private BackendResult searchQdrant(String query) {
        long start = System.nanoTime();
        var tool = qdrant.getIfAvailable();
        if (tool == null) return new BackendResult(RetrievalBackend.QDRANT, Status.UNAVAILABLE,
                elapsed(start), 0, null, null, "NOT_CONFIGURED", List.of());
        try {
            var result = tool.searchTop15(context(), query);
            List<Hit> hits = new ArrayList<>();
            for (var hit : result) hits.add(new Hit(hits.size() + 1, hit.title(), hit.lawId(), hit.externalId(),
                    hit.effectiveDate(), hit.unitId(), hit.articleText(), com.MoleLaw_backend.law.util.LawSourceUrl.publicLink(hit.sourceUrl())));
            return new BackendResult(RetrievalBackend.QDRANT, hits.isEmpty() ? Status.EMPTY : Status.AVAILABLE,
                    elapsed(start), hits.size(), null, null, null, List.copyOf(hits));
        } catch (LegalToolException failure) { return failed(RetrievalBackend.QDRANT, start, failure); }
    }

    private static BackendResult failed(RetrievalBackend backend, long start, LegalToolException failure) {
        return new BackendResult(backend, failure.getReason() == LegalToolException.Reason.BUSY ? Status.BUSY : Status.UNAVAILABLE,
                elapsed(start), 0, null, null, failure.getReason().name(), List.of());
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static LegalToolContext context() {
        var clock = Clock.systemUTC();
        return new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION, UUID.randomUUID().toString(),
                clock.instant().plus(Duration.ofMinutes(3)), 64, clock);
    }
}
