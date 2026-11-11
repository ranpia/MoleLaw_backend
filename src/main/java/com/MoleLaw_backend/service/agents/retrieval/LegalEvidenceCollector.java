package com.MoleLaw_backend.service.agents.retrieval;

import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.dto.retrieval.RetrievalBackend;
import com.MoleLaw_backend.dto.retrieval.RetrievalObserver;
import com.MoleLaw_backend.service.agents.tool.LawRetrievalToolFactory;
import com.MoleLaw_backend.service.agents.tool.LegalToolContext;
import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.LegalToolException;
import com.MoleLaw_backend.util.exception.MolelawException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** First-question boundary; a bounded tool-calling model chooses search/read/collection operations. */
@Slf4j
@Service
public class LegalEvidenceCollector {
    private final QuestionRetrievalPlanner questions;
    private final AiSearchEvidenceCollector aiSearch;
    private final LawRetrievalToolFactory tools;
    private final LawToolCallingPlanner planner;
    private final RetrievalBackend backend;
    private final Duration processingTimeout;
    private final boolean fixedIndexOnly;

    public LegalEvidenceCollector(QuestionRetrievalPlanner questions, AiSearchEvidenceCollector aiSearch,
            LawRetrievalToolFactory tools, LawToolCallingPlanner planner,
            @Value("${app.law-retrieval.backend:QDRANT}") RetrievalBackend backend,
            @Value("${app.law-retrieval.processing-timeout:3m}") Duration processingTimeout,
            @Value("${app.law-retrieval.fixed-index-only:false}") boolean fixedIndexOnly) {
        if (processingTimeout == null || processingTimeout.isNegative() || processingTimeout.isZero()
                || processingTimeout.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("Invalid processing timeout");
        this.questions = questions; this.aiSearch = aiSearch; this.tools = tools; this.planner = planner;
        this.backend = backend; this.processingTimeout = processingTimeout; this.fixedIndexOnly = fixedIndexOnly;
    }

    public List<EvidenceItem> collect(String question) { return collectForAnswer(question).evidence(); }
    public CollectedLegalEvidence collectForAnswer(String question) { return collectForAnswer(question, RetrievalObserver.NONE); }

    public CollectedLegalEvidence collectForAnswer(String question, RetrievalObserver observer) {
        if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000)
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        if (fixedIndexOnly && backend != RetrievalBackend.QDRANT) throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        if (backend == RetrievalBackend.AI_SEARCH) return aiSearch.collect(question, observer);
        tools.requireAvailable();
        var clock = Clock.systemUTC();
        var context = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION,
                observer.traceId() == null ? UUID.randomUUID().toString() : observer.traceId(),
                clock.instant().plus(processingTimeout), 128, clock);
        observer.progress("QUESTION_PLANNING");
        var plan = questions.plan(question);
        context.checkDeadline();
        if (plan.type() == QuestionRetrievalPlan.Type.OUT_OF_SCOPE) throw new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT);
        try {
            return planner.collect(question, plan, tools.create(question, plan, context, observer, fixedIndexOnly), observer);
        } catch (LegalToolException failure) {
            log.warn("Law tool executionId={} reason={} calls={}", context.executionId(), failure.getReason(), context.callsUsed());
            throw new MolelawException(switch (failure.getReason()) {
                case BUSY -> ErrorCode.RETRIEVAL_BUSY;
                case BUDGET_EXHAUSTED -> ErrorCode.EVIDENCE_INSUFFICIENT;
                default -> ErrorCode.RETRIEVAL_UNAVAILABLE;
            });
        }
    }
}
