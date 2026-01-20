package org.javai.outcome.retry;

import java.time.Duration;
import java.util.Objects;

/**
 * The decision made by a retry policy after evaluating a failure.
 */
public sealed interface RetryDecision permits RetryDecision.Retry, RetryDecision.GiveUp {

    /**
     * Retry the operation after waiting for the specified delay.
     */
    record Retry(Duration delay) implements RetryDecision {
        public Retry {
            Objects.requireNonNull(delay, "delay must not be null");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
        }

        public static Retry immediate() {
            return new Retry(Duration.ZERO);
        }

        public static Retry after(Duration delay) {
            return new Retry(delay);
        }
    }

    /**
     * Do not retry; accept the failure.
     */
    record GiveUp(String reason) implements RetryDecision {
        public GiveUp() {
            this(null);
        }

        public static GiveUp because(String reason) {
            return new GiveUp(reason);
        }
    }
}
