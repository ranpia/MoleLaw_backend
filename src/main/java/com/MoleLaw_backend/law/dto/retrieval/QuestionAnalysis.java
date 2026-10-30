package com.MoleLaw_backend.law.dto.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** User intent and source-grounded facts; search candidates are never legal evidence. */
public record QuestionAnalysis(String title, String purpose, List<Fact> facts, List<Issue> issues,
        List<Relation> relations, List<String> ambiguities) {
    public QuestionAnalysis {
        title = text(title, 100);
        purpose = text(purpose, 300);
        facts = items(facts, 16);
        issues = items(issues, 8);
        relations = items(relations, 16);
        ambiguities = texts(ambiguities, 8, 200);
        Set<String> ids = new HashSet<>();
        for (var issue : issues) if (!ids.add(issue.id())) throw invalid();
        for (var relation : relations)
            if (!ids.contains(relation.from()) || !ids.contains(relation.to())
                    || relation.from().equals(relation.to())) throw invalid();
    }

    public record Fact(String text, String sourceQuote) {
        public Fact { text = QuestionAnalysis.text(text, 300); sourceQuote = QuestionAnalysis.text(sourceQuote, 500); }
    }

    public record Issue(String id, String request, String sourceQuote, List<String> searchTerms,
            List<String> mentionedTargets, List<String> candidateTargets, List<String> missingFacts) {
        public Issue {
            id = text(id, 16);
            if (!id.matches("Q[1-8]")) throw invalid();
            request = text(request, 300);
            sourceQuote = text(sourceQuote, 1000);
            searchTerms = texts(searchTerms, 6, 200);
            if (searchTerms.isEmpty()) throw invalid();
            mentionedTargets = texts(mentionedTargets, 8, 100);
            candidateTargets = texts(candidateTargets, 8, 100);
            missingFacts = texts(missingFacts, 6, 200);
        }
    }

    public enum RelationType { COMPARISON, CONFLICT, PREREQUISITE, EXCEPTION, TEMPORAL, RELATED }
    public record Relation(String from, String to, RelationType type) {
        public Relation {
            from = text(from, 16); to = text(to, 16);
            if (type == null) throw invalid();
        }
    }

    /** Reject fabricated quotations and targets instead of silently accepting an invented intent. */
    public void validateAgainst(String question, boolean outOfScope) {
        if (question == null || question.isBlank() || (!outOfScope && issues.isEmpty())) throw invalid();
        for (var fact : facts) if (!question.contains(fact.sourceQuote())) throw invalid();
        for (var issue : issues) {
            if (!question.contains(issue.sourceQuote())) throw invalid();
            for (String target : issue.mentionedTargets()) if (!question.contains(target)) throw invalid();
        }
    }

    private static String text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw invalid();
        return value.strip();
    }
    private static List<String> texts(List<String> values, int count, int length) {
        return items(values, count).stream().map(value -> text(value, length)).toList();
    }
    private static <T> List<T> items(List<T> values, int count) {
        if (values == null || values.size() > count || values.stream().anyMatch(value -> value == null)
                || new HashSet<>(values).size() != values.size()) throw invalid();
        return List.copyOf(values);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid question analysis"); }
}
