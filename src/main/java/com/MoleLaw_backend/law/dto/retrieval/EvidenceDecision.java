package com.MoleLaw_backend.law.dto.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.MoleLaw_backend.law.exception.EvidenceDecisionValidationException;
import com.MoleLaw_backend.law.exception.EvidenceDecisionValidationException.Code;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/** Internal LLM proposal. It never grants tool permissions or decides execution budgets. */
public record EvidenceDecision(Action action, String reason, List<String> missingIssues,
                               List<String> evidenceIds, List<String> searchQueries,
                               List<LawSearch> lawSearches) {
    public enum Action { ANSWER_READY, SEARCH_AGAIN, COLLECT_LAW, INSUFFICIENT }
    public enum SearchField { TITLE, BODY }

    /** Search intent only: paging, source IDs, credentials and execution limits are server-owned. */
    public record LawSearch(String query, SearchField field, boolean exactName, String ministry) {
        public LawSearch {
            query = text(query, 200);
            if (field == null || (exactName && field != SearchField.TITLE)
                    || (ministry != null && !ministry.matches("[0-9]{1,10}"))) throw invalid();
        }
    }

    public EvidenceDecision {
        if (action == null) throw invalid();
        reason = text(reason, 1000);
        missingIssues = texts(missingIssues, 8, 300);
        evidenceIds = texts(evidenceIds, 15, 128);
        searchQueries = texts(searchQueries, 3, 200);
        if (lawSearches == null || lawSearches.size() > 3 || lawSearches.stream().anyMatch(item -> item == null)) {
            throw invalid();
        }
        lawSearches = List.copyOf(lawSearches);
        if (new HashSet<>(lawSearches).size() != lawSearches.size()) throw invalid();
        boolean valid = switch (action) {
            case ANSWER_READY -> !evidenceIds.isEmpty() && missingIssues.isEmpty()
                    && searchQueries.isEmpty() && lawSearches.isEmpty();
            case SEARCH_AGAIN -> !missingIssues.isEmpty() && !searchQueries.isEmpty() && lawSearches.isEmpty();
            case COLLECT_LAW -> !missingIssues.isEmpty() && !lawSearches.isEmpty() && searchQueries.isEmpty();
            case INSUFFICIENT -> !missingIssues.isEmpty() && searchQueries.isEmpty() && lawSearches.isEmpty();
        };
        if (!valid) throw new EvidenceDecisionValidationException(Code.ACTION_CONTRACT);
    }

    /** The caller supplies only IDs actually shown to the model in this authorized execution. */
    public void validateAgainst(Collection<String> suppliedEvidenceIds) {
        if (suppliedEvidenceIds == null || !new HashSet<>(suppliedEvidenceIds).containsAll(evidenceIds)) {
            throw new EvidenceDecisionValidationException(Code.UNKNOWN_EVIDENCE_ID);
        }
    }

    /** Strict boundary for model output: no unknown fields, extra JSON values or missing fields. */
    public static EvidenceDecision parse(String json, Collection<String> suppliedEvidenceIds, ObjectMapper mapper) {
        if (json == null || json.length() > 32_768 || mapper == null) throw invalid();
        try {
            EvidenceDecision decision = mapper.readerFor(EvidenceDecision.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .readValue(json);
            if (decision == null) throw invalid();
            decision.validateAgainst(suppliedEvidenceIds);
            return decision;
        } catch (JsonProcessingException failure) {
            // Never propagate model output in errors or logs.
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof EvidenceDecisionValidationException validation) throw validation;
                cause = cause.getCause();
            }
            throw new EvidenceDecisionValidationException(Code.JSON_CONTRACT);
        }
    }

    private static List<String> texts(List<String> values, int maxItems, int maxLength) {
        if (values == null || values.size() > maxItems) throw invalid();
        List<String> result = values.stream().map(value -> text(value, maxLength)).toList();
        if (new HashSet<>(result).size() != result.size()) throw invalid();
        return result;
    }

    private static String text(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) throw invalid();
        return value.strip();
    }

    private static IllegalArgumentException invalid() {
        return new EvidenceDecisionValidationException(Code.FIELD_CONSTRAINT);
    }
}
