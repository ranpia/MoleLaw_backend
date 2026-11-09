package com.MoleLaw_backend.service.tool;

import com.MoleLaw_backend.config.LawCandidateSelectionProperties;
import com.MoleLaw_backend.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.dto.retrieval.RetrievalObserver;
import com.MoleLaw_backend.service.retrieval.LawCandidateSelector;
import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.MolelawException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LawRetrievalToolFactory {
    private final ObjectProvider<LawRagSearchToolService> searches;
    private final ObjectProvider<LawVectorizationToolService> vectors;
    private final LegalApiToolService laws;
    private final LawCandidateSelector candidates;
    private final EvidenceReadToolService reader;
    private final LawCandidateSelectionProperties selection;
    private final ObjectMapper mapper;
    private final int bodyLimit;

    public LawRetrievalToolFactory(ObjectProvider<LawRagSearchToolService> searches,
            ObjectProvider<LawVectorizationToolService> vectors, LegalApiToolService laws,
            LawCandidateSelector candidates, EvidenceReadToolService reader, LawCandidateSelectionProperties selection,
            ObjectMapper mapper, @Value("${app.ai.evidence.body-max-input-tokens:12000}") int bodyLimit) {
        if (bodyLimit < 128 || bodyLimit > 64000) throw new IllegalArgumentException("Invalid law body budget");
        this.searches = searches; this.vectors = vectors; this.laws = laws; this.candidates = candidates;
        this.reader = reader; this.selection = selection; this.mapper = mapper; this.bodyLimit = bodyLimit;
    }

    public LawRetrievalToolSession create(String question, QuestionRetrievalPlan plan, LegalToolContext context,
            RetrievalObserver observer, boolean fixedIndexOnly) {
        var search = searches.getIfAvailable();
        var vector = vectors.getIfAvailable();
        if (search == null || vector == null) throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        return new LawRetrievalToolSession(question, plan, context, search, vector, laws, candidates, reader,
                selection, observer, fixedIndexOnly, bodyLimit, mapper);
    }

    public void requireAvailable() {
        if (searches.getIfAvailable() == null || vectors.getIfAvailable() == null)
            throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
    }
}
