package com.MoleLaw_backend.law.service.tool;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class LegalToolContextEmbeddingTimeTest {
    private final MutableClock clock = new MutableClock();
    private final LegalToolContext context = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION,
            "time-budget-test", clock.instant().plusSeconds(60), 128, clock);

    @Test
    void embeddingLongerThanTheOuterLimitLeavesTheRemainingConsultationTimeIntact() {
        clock.advance(10);
        try (var ignored = context.embeddingTime(Duration.ofMinutes(15))) {
            clock.advance(480);
            context.checkDeadline();
            context.reserve();
        }
        clock.advance(49);
        context.checkDeadline();
        clock.advance(1);
        assertThatThrownBy(context::checkDeadline).hasMessage("BUDGET_EXHAUSTED");
        assertThat(context.callsUsed()).isEqualTo(1);
    }

    @Test
    void allEmbeddingBatchesShareOneCumulativeTimeLimit() {
        try (var ignored = context.embeddingTime(Duration.ofSeconds(10))) { clock.advance(6); }
        clock.advance(2); // Non-embedding processing consumes only the outer budget.
        assertThatThrownBy(() -> {
            try (var ignored = context.embeddingTime(Duration.ofSeconds(10))) { clock.advance(4); }
        }).hasMessage("EMBEDDING_TIMEOUT");
        context.checkDeadline();
        assertThatThrownBy(() -> context.embeddingTime(Duration.ofSeconds(10))).hasMessage("EMBEDDING_TIMEOUT");
    }

    @Test
    void exhaustedConsultationCannotBeRevivedByStartingAnEmbedding() {
        clock.advance(60);
        assertThatThrownBy(() -> context.embeddingTime(Duration.ofMinutes(15))).hasMessage("BUDGET_EXHAUSTED");
    }

    @Test
    void closeCannotExtendTheOuterClockTwice() {
        var scope = context.embeddingTime(Duration.ofMinutes(15));
        clock.advance(70);
        scope.close();
        clock.advance(10);
        scope.close();
        context.checkDeadline();
        clock.advance(50);
        assertThatThrownBy(context::checkDeadline).hasMessage("BUDGET_EXHAUSTED");
    }

    @Test
    void providerFailureStillRestoresTheOuterClock() {
        assertThatThrownBy(() -> {
            try (var ignored = context.embeddingTime(Duration.ofMinutes(15))) {
                clock.advance(70);
                throw new IllegalStateException("provider failure");
            }
        }).hasMessage("provider failure");
        context.checkDeadline();
        clock.advance(60);
        assertThatThrownBy(context::checkDeadline).hasMessage("BUDGET_EXHAUSTED");
    }

    @Test
    void embeddingScopesDoNotBypassTheHttpCallLimit() {
        var limited = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION, "call-budget",
                clock.instant().plusSeconds(60), 1, clock);
        try (var ignored = limited.embeddingTime(Duration.ofMinutes(15))) {
            limited.reserve();
            assertThatThrownBy(limited::reserve).hasMessage("BUDGET_EXHAUSTED");
        }
    }

    @Test
    void retrievalEmbeddingAndIndexCallsAreIndependentAndNeverReset() {
        var limited = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION, "split",
                clock.instant().plusSeconds(60), 1, clock);
        limited.configureVectorCallLimits(2, 2);
        limited.reserve();
        limited.reserveEmbedding();
        limited.reserveEmbedding();
        limited.reserveIndex();
        limited.reserveIndex();
        limited.configureVectorCallLimits(2, 2);
        assertThat(limited.callsUsed()).isEqualTo(5);
        assertThatThrownBy(limited::reserveEmbedding).hasMessage("BUDGET_EXHAUSTED");
        assertThat(limited.callBudgets()).containsEntry("exhausted", "EMBEDDING");
        assertThatThrownBy(limited::reserveIndex).hasMessage("BUDGET_EXHAUSTED");
        assertThat(limited.callBudgets()).containsEntry("exhausted", "INDEXING");
        assertThatThrownBy(limited::reserve).hasMessage("BUDGET_EXHAUSTED");
        assertThatThrownBy(() -> limited.configureVectorCallLimits(3, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hundredsOfVectorCallsCannotConsumeRetrievalAllowance() {
        var limited = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION, "large-law",
                clock.instant().plusSeconds(60), 1, clock);
        limited.configureVectorCallLimits(512, 512);
        for (int i = 0; i < 300; i++) {
            limited.reserveEmbedding();
            limited.reserveIndex();
        }
        assertThat(limited.hasBudget()).isTrue();
        limited.reserve();
        assertThat(limited.hasBudget()).isFalse();
    }

    @Test
    void followUpCannotUseAnEmbeddingScope() {
        var followUp = new LegalToolContext(LegalToolContext.Purpose.FOLLOW_UP, "follow-up",
                clock.instant().plusSeconds(60), 128, clock);
        assertThatThrownBy(() -> followUp.embeddingTime(Duration.ofMinutes(15))).hasMessage("FORBIDDEN");
        assertThatThrownBy(followUp::reserveEmbedding).hasMessage("FORBIDDEN");
        assertThatThrownBy(followUp::reserveIndex).hasMessage("FORBIDDEN");
    }

    static final class MutableClock extends Clock {
        private Instant time = Instant.parse("2026-10-22T00:00:00Z");
        void advance(long seconds) { time = time.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
}
