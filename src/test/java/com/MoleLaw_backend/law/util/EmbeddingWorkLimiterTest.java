package com.MoleLaw_backend.law.util;
import com.MoleLaw_backend.law.exception.LegalToolException;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingWorkLimiterTest {
    @Test void aFullQueueFailsImmediatelyAndClosingTwiceDoesNotIncreaseCapacity() {
        var limiter = new EmbeddingWorkLimiter(1, 0, 5000);
        var active = limiter.acquire();
        assertThatThrownBy(limiter::acquire).isInstanceOfSatisfying(LegalToolException.class,
                failure -> assertThat(failure.getReason()).isEqualTo(LegalToolException.Reason.BUSY));
        active.close();
        active.close();
        try (var next = limiter.acquire()) {
            assertThatThrownBy(limiter::acquire).isInstanceOf(LegalToolException.class);
        }
    }

    @Test void aTimedOutWaiterFreesItsQueueSlotAndDoesNotLeakARunningPermit() throws Exception {
        var limiter = new EmbeddingWorkLimiter(1, 1, 20);
        var executor = Executors.newSingleThreadExecutor();
        try (var active = limiter.acquire()) {
            executor.submit(() -> assertThatThrownBy(limiter::acquire)
                    .isInstanceOf(LegalToolException.class)).get(2, TimeUnit.SECONDS);
            executor.submit(() -> assertThatThrownBy(limiter::acquire)
                    .isInstanceOf(LegalToolException.class)).get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        try (var next = limiter.acquire()) {
            assertThat(next).isNotNull();
        }
    }

    @Test void interruptionPreservesInterruptStatusAndReleasesAdmission() throws Exception {
        var limiter = new EmbeddingWorkLimiter(1, 1, 5000);
        var executor = Executors.newSingleThreadExecutor();
        try (var active = limiter.acquire()) {
            assertThat(executor.submit(() -> {
                Thread.currentThread().interrupt();
                try { limiter.acquire(); return false; }
                catch (LegalToolException busy) { return Thread.currentThread().isInterrupted(); }
                finally { Thread.interrupted(); }
            }).get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        try (var next = limiter.acquire()) { assertThat(next).isNotNull(); }
    }
}
