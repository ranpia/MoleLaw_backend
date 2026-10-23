package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.exception.LegalToolException;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.MoleLaw_backend.law.exception.LegalToolException.Reason.*;

/** One context per authorized server execution, never deserialized from model/user input.
 * The caller must check authentication and room ownership before constructing it.
 */
public final class LegalToolContext {
    public enum Purpose { FIRST_QUESTION, PRECOLLECTION, FOLLOW_UP, FOLLOW_UP_CASE }
    private final Purpose purpose;
    private final String executionId;
    private Instant deadline;
    private Duration embeddingLimit;
    private Duration embeddingUsed = Duration.ZERO;
    private EmbeddingTime activeEmbedding;
    private final int maxCalls;
    private final Clock clock;
    private int calls;
    private int embeddingCalls;
    private int indexCalls;
    private int maxEmbeddingCalls = 1024;
    private int maxIndexCalls = 1024;
    private boolean vectorLimitsConfigured;
    private String exhaustedBudget;
    final Set<String> completed = new HashSet<>();
    final Set<String> exhausted = new HashSet<>();
    final Map<String, Integer> endPages = new HashMap<>();
    final Map<String, Set<String>> seen = new HashMap<>();
    final Map<String, LegalToolContracts.Candidate> laws = new HashMap<>();
    final Map<String, LegalToolContracts.Candidate> cases = new HashMap<>();
    final Map<String, LegalToolContracts.DetailResult> details = new HashMap<>();

    public LegalToolContext(Purpose purpose, String executionId, Instant deadline, int maxCalls, Clock clock) {
        if (purpose == null || executionId == null || executionId.isBlank() || deadline == null
                || maxCalls < 1 || clock == null) {
            throw new IllegalArgumentException("Invalid server execution context");
        }
        this.purpose = purpose;
        this.executionId = executionId;
        this.deadline = deadline;
        this.maxCalls = maxCalls;
        this.clock = clock;
    }

    void authorize() {
        if (purpose == Purpose.FOLLOW_UP || purpose == Purpose.FOLLOW_UP_CASE)
            throw new LegalToolException(FORBIDDEN, false);
    }

    void authorizeCases() {
        if (purpose == Purpose.FOLLOW_UP) throw new LegalToolException(FORBIDDEN, false);
    }

    void reserveCase() {
        authorizeCases();
        reserveCall();
    }

    Instant now() { return clock.instant(); }
    boolean hasBudget() {
        if (calls >= maxCalls) { exhaustedBudget = "RETRIEVAL"; return false; }
        return activeEmbedding == null ? now().isBefore(deadline) : now().isBefore(activeEmbedding.expiresAt);
    }
    void reserve() {
        authorize();
        reserveCall();
    }

    private void reserveCall() {
        checkDeadline();
        if (calls >= maxCalls) throw exhausted("RETRIEVAL");
        calls++;
    }
    /** Trusted configuration only; never reset counters or increase limits mid-execution. */
    void configureVectorCallLimits(int embedding, int indexing) {
        authorize();
        if (embedding < 1 || indexing < 1) throw new IllegalArgumentException("Invalid vector call limits");
        if (vectorLimitsConfigured && (embedding != maxEmbeddingCalls || indexing != maxIndexCalls))
            throw new IllegalArgumentException("Vector call limits cannot change during execution");
        if (!vectorLimitsConfigured && (embeddingCalls != 0 || indexCalls != 0))
            throw new IllegalStateException("Vector limits must be configured before use");
        maxEmbeddingCalls = embedding;
        maxIndexCalls = indexing;
        vectorLimitsConfigured = true;
    }
    public void reserveEmbedding() {
        authorize();
        checkDeadline();
        if (embeddingCalls >= maxEmbeddingCalls) throw exhausted("EMBEDDING");
        embeddingCalls++;
    }
    public void reserveIndex() {
        authorize();
        checkDeadline();
        if (indexCalls >= maxIndexCalls) throw exhausted("INDEXING");
        indexCalls++;
    }
    private LegalToolException exhausted(String budget) {
        exhaustedBudget = budget;
        return new LegalToolException(BUDGET_EXHAUSTED, false);
    }
    public Map<String, Object> callBudgets() {
        return Map.of("retrievalUsed", calls, "retrievalLimit", maxCalls,
                "embeddingUsed", embeddingCalls, "embeddingLimit", maxEmbeddingCalls,
                "indexUsed", indexCalls, "indexLimit", maxIndexCalls,
                "exhausted", exhaustedBudget == null ? "NONE" : exhaustedBudget);
    }
    public void checkDeadline() {
        if (activeEmbedding != null) {
            if (!now().isBefore(activeEmbedding.expiresAt)) throw new LegalToolException(EMBEDDING_TIMEOUT, false);
        } else if (!now().isBefore(deadline)) {
            throw exhausted("PROCESSING_TIME");
        }
    }

    /** The accumulated embedding time (including queue waits) is excluded from the outer execution clock. */
    public EmbeddingTime embeddingTime(Duration limit) {
        authorize();
        if (limit == null || limit.isZero() || limit.isNegative()) throw new IllegalArgumentException("Invalid embedding timeout");
        if (activeEmbedding != null) throw new IllegalStateException("Embedding scopes cannot nest");
        checkDeadline(); // Never revive an already expired consultation.
        if (embeddingLimit == null) embeddingLimit = limit;
        else if (!embeddingLimit.equals(limit)) throw new IllegalArgumentException("Embedding limit cannot change during an execution");
        Duration remaining = embeddingLimit.minus(embeddingUsed);
        if (remaining.isZero() || remaining.isNegative()) throw new LegalToolException(EMBEDDING_TIMEOUT, false);
        activeEmbedding = new EmbeddingTime(now(), remaining);
        return activeEmbedding;
    }

    public final class EmbeddingTime implements AutoCloseable {
        private final Instant startedAt;
        private final Instant expiresAt;
        private boolean closed;

        private EmbeddingTime(Instant startedAt, Duration remaining) {
            this.startedAt = startedAt;
            this.expiresAt = startedAt.plus(remaining);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Duration elapsed = Duration.between(startedAt, now());
            if (elapsed.isNegative()) elapsed = Duration.ZERO;
            embeddingUsed = embeddingUsed.plus(elapsed);
            deadline = deadline.plus(elapsed);
            activeEmbedding = null;
            if (embeddingUsed.compareTo(embeddingLimit) >= 0) throw new LegalToolException(EMBEDDING_TIMEOUT, false);
        }
    }
    public synchronized int callsUsed() { return calls + embeddingCalls + indexCalls; }
    public String executionId() { return executionId; }
}
