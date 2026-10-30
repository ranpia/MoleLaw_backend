package com.MoleLaw_backend.law.dto.retrieval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.HashSet;

/** Read-only model proposal; never grants execution permissions or sets budgets. */
public record QuestionRetrievalPlan(Type type, ResponseScope responseScope, List<String> searchQueries,
        List<EvidenceDecision.LawSearch> lawSearches, List<String> missingFacts, QuestionAnalysis analysis) {
    public enum Type { LAW_DISCOVERY, LAW_EXPLANATION, CASE_ASSESSMENT, PROCEDURE_GUIDANCE, OUT_OF_SCOPE }
    public enum ResponseScope { GENERAL_GUIDANCE, SPECIFIC_ASSESSMENT }

    /** Compatibility for server-created plans; model output must include a grounded analysis. */
    public QuestionRetrievalPlan(Type type, ResponseScope responseScope, List<String> searchQueries,
            List<EvidenceDecision.LawSearch> lawSearches, List<String> missingFacts) {
        this(type, responseScope, searchQueries, lawSearches, missingFacts, null);
    }

    public List<String> initialSearchQueries() {
        return analysis == null || analysis.issues().isEmpty() ? searchQueries
                : analysis.issues().stream().map(issue -> issue.searchTerms().get(0)).distinct().toList();
    }

    public QuestionRetrievalPlan {
        if (type == null || responseScope == null) throw invalid();
        searchQueries = texts(searchQueries, 3, 200);
        missingFacts = texts(missingFacts, 6, 200);
        if (lawSearches == null || lawSearches.size() > 3 || lawSearches.stream().anyMatch(value -> value == null)
                || new HashSet<>(lawSearches).size() != lawSearches.size()) throw invalid();
        lawSearches = List.copyOf(lawSearches);
        if (type == Type.OUT_OF_SCOPE) {
            if (!searchQueries.isEmpty() || !lawSearches.isEmpty() || responseScope != ResponseScope.GENERAL_GUIDANCE) throw invalid();
        } else if (searchQueries.isEmpty()) throw invalid();
        if (responseScope == ResponseScope.SPECIFIC_ASSESSMENT
                && (type != Type.CASE_ASSESSMENT || !missingFacts.isEmpty()
                || (analysis != null && analysis.issues().stream().anyMatch(issue -> !issue.missingFacts().isEmpty())))) throw invalid();
    }

    public static QuestionRetrievalPlan parse(String json, ObjectMapper mapper) {
        if (json == null || json.length() > 32768) throw invalid();
        try {
            QuestionRetrievalPlan plan = mapper.readerFor(QuestionRetrievalPlan.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).readValue(json);
            if (plan == null) throw invalid();
            return plan;
        } catch (JsonProcessingException failure) { throw invalid(); }
    }

    private static List<String> texts(List<String> values, int count, int length) {
        if (values == null || values.size() > count || values.stream()
                .anyMatch(value -> value == null || value.isBlank() || value.length() > length)) throw invalid();
        var cleaned = values.stream().map(String::strip).toList();
        if (new HashSet<>(cleaned).size() != cleaned.size()) throw invalid();
        return cleaned;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid question retrieval plan"); }
}
