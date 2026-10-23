package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;
import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.service.tool.LawAiSearchToolService;
import com.MoleLaw_backend.law.service.tool.LegalToolContext;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Article API route without requiring Qdrant or building an index during the question.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiSearchEvidenceCollector {
    private final LawAiSearchToolService search;
    private final QuestionRetrievalPlanner questions;
    private final EvidenceDecisionPlanner planner;

    public CollectedLegalEvidence collect(String question) {
        if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000)
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        var clock = Clock.systemUTC();
        var context = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION, UUID.randomUUID().toString(),
                clock.instant().plus(Duration.ofMinutes(3)), 24, clock);
        try {
            var plan = questions.plan(question);
            context.checkDeadline();
            if (plan.type() == QuestionRetrievalPlan.Type.OUT_OF_SCOPE) throw insufficient();
            List<EvidenceItem> evidence = List.of();
            List<RetrievalAttempt> attempts = new ArrayList<>();
            Map<String, Integer> pages = new HashMap<>();
            for (String query : plan.searchQueries()) evidence = retrieve(context, query, pages, attempts, evidence);
            for (int round = 0; round <= 4; round++) {
                context.checkDeadline();
                var decision = planner.decide(question, evidence, plan, List.copyOf(attempts), 4 - round);
                context.checkDeadline();
                decision.validateAgainst(evidence.stream().map(EvidenceItem::id).toList());
                if (decision.action() == EvidenceDecision.Action.COLLECT_LAW) {
                    log.warn("Legal collection executionId={} backend=AI_SEARCH unsupportedAction=COLLECT_LAW", context.executionId());
                    throw new GptApiException(ErrorCode.GPT_API_FAILURE);
                }
                log.info("Legal collection executionId={} backend=AI_SEARCH round={} action={} evidenceCount={}",
                        context.executionId(), round, decision.action(), evidence.size());
                if (decision.action() == EvidenceDecision.Action.ANSWER_READY) {
                    Set<String> ids = Set.copyOf(decision.evidenceIds());
                    return new CollectedLegalEvidence(plan, evidence.stream().filter(e -> ids.contains(e.id())).toList());
                }
                if (round == 4 || decision.action() != EvidenceDecision.Action.SEARCH_AGAIN) throw insufficient();
                for (String query : decision.searchQueries())
                    evidence = retrieve(context, query, pages, attempts, evidence);
            }
            throw insufficient();
        } catch (LegalToolException failure) {
            log.warn("Legal collection executionId={} backend=AI_SEARCH reason={}", context.executionId(), failure.getReason());
            if (failure.getReason() == LegalToolException.Reason.BUDGET_EXHAUSTED) throw insufficient();
            throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        }
    }

    private List<EvidenceItem> retrieve(LegalToolContext context, String query, Map<String, Integer> pages,
                                        List<RetrievalAttempt> attempts, List<EvidenceItem> previous) {
        int page = pages.getOrDefault(query, 1);
        if (page > 3) {
            attempts.add(new RetrievalAttempt(RetrievalAttempt.Kind.AI_SEARCH, query, null, null,
                    RetrievalAttempt.Outcome.EXHAUSTED, 0, false));
            return previous;
        }
        var result = search.search(context, new AiLawSearchRequest(query, page, 15));
        pages.put(query, result.hasNext() ? page + 1 : 4);
        attempts.add(new RetrievalAttempt(RetrievalAttempt.Kind.AI_SEARCH, query, null, page,
                result.evidence().isEmpty() ? RetrievalAttempt.Outcome.NO_RESULTS : RetrievalAttempt.Outcome.AVAILABLE,
                result.evidence().size(), result.hasNext()));
        var merged = new LinkedHashMap<String, EvidenceItem>();
        result.evidence().forEach(e -> merged.putIfAbsent(e.sourceId(), e));
        previous.forEach(e -> merged.putIfAbsent(e.sourceId(), e));
        return merged.values().stream().limit(15).toList();
    }

    private static MolelawException insufficient() {
        return new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT);
    }
}
