package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceReading;

/** Bounds planning data by tokens while retaining whole articles and the retrieval ranking. */
public final class EvidenceDecisionInputBuilder {
    private final ObjectMapper mapper;
    private final int maxTokens;
    private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);

    public EvidenceDecisionInputBuilder(ObjectMapper mapper, int maxTokens) {
        if (maxTokens < 128) throw new IllegalArgumentException("Planning input limit is too small");
        this.mapper = mapper;
        this.maxTokens = maxTokens;
    }

    public ObjectNode build(String question, List<EvidenceItem> evidence) {
        return build(question, evidence, null, List.of());
    }

    /** All read bodies are supplied intact; catalog-only IDs never become admissible citations. */
    public ObjectNode buildRead(String question, EvidenceReading reading, QuestionRetrievalPlan plan,
            List<RetrievalAttempt> attempts, int remainingActions, int totalLimit) {
        return buildRead(question, reading, plan, attempts, remainingActions, totalLimit, null);
    }

    public ObjectNode buildRead(String question, EvidenceReading reading, QuestionRetrievalPlan plan,
            List<RetrievalAttempt> attempts, int remainingActions, int totalLimit, List<String> indexedTitles) {
        var input = build(question, List.of(), plan, attempts, remainingActions);
        if (indexedTitles != null) {
            input.set("indexedLawTitles", mapper.valueToTree(indexedTitles.stream().limit(20).toList()));
            input.put("indexedLawTitlesComplete", indexedTitles.size() <= 20);
        }
        input.set("candidateCatalog", reading.catalog());
        input.set("readOutcomes", mapper.valueToTree(reading.outcomes()));
        var supplied = (com.fasterxml.jackson.databind.node.ArrayNode) input.path("evidence");
        for (var item : reading.evidence()) supplied.add(evidenceView(item));
        if (tokens.estimate(input.toString()) > totalLimit)
            throw new IllegalArgumentException("Combined evidence decision input exceeds budget");
        return input;
    }

    private ObjectNode evidenceView(EvidenceItem item) {
        var view = mapper.createObjectNode().put("id", item.id()).put("kind", item.kind())
                .put("title", item.title()).put("content", item.content());
        if (item.provenance() != null) {
            var provenance = item.provenance();
            view.put("mst", provenance.mst()).put("lawId", provenance.lawId())
                    .put("effectiveDate", provenance.effectiveDate()).put("articlePath", provenance.articlePath());
        }
        return view;
    }

    public ObjectNode build(String question, List<EvidenceItem> evidence, QuestionRetrievalPlan plan,
            List<RetrievalAttempt> attempts) {
        return build(question, evidence, plan, attempts, 4);
    }

    public ObjectNode build(String question, List<EvidenceItem> evidence, QuestionRetrievalPlan plan,
            List<RetrievalAttempt> attempts, int remainingActions) {
        if (remainingActions < 0 || remainingActions > 4) throw new IllegalArgumentException("Invalid remaining actions");
        ObjectNode input = mapper.createObjectNode().put("question", question);
        boolean aiSearch = attempts.stream().anyMatch(a -> a.kind() == RetrievalAttempt.Kind.AI_SEARCH);
        var searches = attempts.stream().filter(attempt -> attempt.kind() != RetrievalAttempt.Kind.LAW_COLLECTION).toList();
        boolean reviseQuery = !searches.isEmpty() && searches.get(searches.size() - 1).outcome() != RetrievalAttempt.Outcome.AVAILABLE;
        input.putObject("executionState").put("remainingActions", remainingActions)
                .put("vectorSearchCount", attempts.stream().filter(a -> a.kind() == RetrievalAttempt.Kind.VECTOR_SEARCH
                        && a.outcome() != RetrievalAttempt.Outcome.DUPLICATE).count())
                .put("skippedDuplicateQueryCount", attempts.stream().filter(a -> a.kind() == RetrievalAttempt.Kind.VECTOR_SEARCH
                        && a.outcome() == RetrievalAttempt.Outcome.DUPLICATE).count())
                .put("lawSearchCount", attempts.stream().filter(a -> a.kind() == RetrievalAttempt.Kind.LAW_SEARCH
                        && a.outcome() != RetrievalAttempt.Outcome.NO_RELEVANT_CANDIDATES).count())
                .put("aiSearchCount", attempts.stream().filter(a -> a.kind() == RetrievalAttempt.Kind.AI_SEARCH
                        && a.outcome() != RetrievalAttempt.Outcome.EXHAUSTED).count())
                .put("retrievalBackend", aiSearch ? "AI_SEARCH" : "QDRANT")
                .put("vectorSearchScope", aiSearch ? "NOT_USED" : "EXISTING_INDEX_ONLY")
                .put("vectorAnnexTextIndexed", false)
                .put("queryRevisionRequired", reviseQuery)
                .put("nonProductiveSearchCount", searches.stream()
                        .filter(a -> a.outcome() != RetrievalAttempt.Outcome.AVAILABLE).count())
                .put("availabilityIsRelevance", false);
        if (plan != null) input.set("retrievalPlan", QuestionPlanInput.view(plan, mapper));
        input.set("retrievalProgress", RetrievalProgressInput.build(plan, attempts, mapper));
        input.set("searchAttempts", mapper.valueToTree(searches.subList(Math.max(0, searches.size() - 8), searches.size())));
        var feedback = (com.fasterxml.jackson.databind.node.ArrayNode) input.path("searchAttempts");
        // Retain the latest observation and reserve space for actual legal evidence.
        while (feedback.size() > 1 && tokens.estimate(input.toString()) > maxTokens / 2) feedback.remove(0);
        var supplied = input.putArray("evidence");
        if (tokens.estimate(input.toString()) > maxTokens) throw new IllegalArgumentException("Question exceeds planning input budget");
        for (var item : evidence) {
            var view = evidenceView(item);
            supplied.add(view);
            if (tokens.estimate(input.toString()) > maxTokens) supplied.remove(supplied.size() - 1);
        }
        // No partial source may be presented as a complete legal article.
        if (!evidence.isEmpty() && supplied.isEmpty()) throw new IllegalArgumentException("No whole article fits planning input budget");
        return input;
    }
}
