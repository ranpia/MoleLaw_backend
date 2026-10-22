package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.exception.LegalToolException;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local CPU backpressure: a bounded number of running and waiting batches. */
public final class EmbeddingWorkLimiter {
    private final Semaphore running;
    private final AtomicInteger admitted = new AtomicInteger();
    private final int capacity;
    private final long waitMillis;

    public EmbeddingWorkLimiter(int concurrency, int queueCapacity, long waitMillis) {
        if (concurrency < 1 || concurrency > 8 || queueCapacity < 0 || queueCapacity > 100
                || waitMillis < 1 || waitMillis > 120_000) {
            throw new IllegalArgumentException("Invalid embedding load limits");
        }
        running = new Semaphore(concurrency, true);
        capacity = concurrency + queueCapacity;
        this.waitMillis = waitMillis;
    }

    public Permit acquire() {
        if (admitted.incrementAndGet() > capacity) {
            admitted.decrementAndGet();
            throw busy();
        }
        try {
            if (!running.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                admitted.decrementAndGet();
                throw busy();
            }
            return new Permit();
        } catch (InterruptedException interrupted) {
            admitted.decrementAndGet();
            Thread.currentThread().interrupt();
            throw busy();
        }
    }

    public final class Permit implements AutoCloseable {
        private boolean released;

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                running.release();
                admitted.decrementAndGet();
            }
        }
    }

    private static LegalToolException busy() {
        return new LegalToolException(LegalToolException.Reason.BUSY, true);
    }
}
