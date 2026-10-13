package com.MoleLaw_backend.law.service.tool;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.MoleLaw_backend.law.service.tool.LegalToolException.Reason.*;

/** One context per authorized server execution, never deserialized from model/user input.
 * The caller must check authentication and room ownership before constructing it.
 */
public final class LegalToolContext {
    public enum Purpose { FIRST_QUESTION, PRECOLLECTION, FOLLOW_UP }
    private final Purpose purpose;
    private final String executionId;
    private final Instant deadline;
    private final int maxCalls;
    private final Clock clock;
    private int calls;
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
        if (purpose == Purpose.FOLLOW_UP) throw new LegalToolException(FORBIDDEN, false);
    }

    Instant now() { return clock.instant(); }
    boolean hasBudget() { return calls < maxCalls && now().isBefore(deadline); }
    void reserve() {
        authorize();
        if (!hasBudget()) throw new LegalToolException(BUDGET_EXHAUSTED, false);
        calls++;
    }
    void checkDeadline() {
        if (!now().isBefore(deadline)) throw new LegalToolException(BUDGET_EXHAUSTED, false);
    }
    public synchronized int callsUsed() { return calls; }
    public String executionId() { return executionId; }
}
