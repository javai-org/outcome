package org.javai.outcome.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Context provided to retry policies for making decisions.
 *
 * @param attemptNumber The current attempt number (1-based)
 * @param startedAt When the first attempt began
 * @param elapsed Time elapsed since the first attempt
 * @param budget Optional time budget remaining (null if no budget)
 */
public record RetryContext(
        int attemptNumber,
        Instant startedAt,
        Duration elapsed,
        Duration budget
) {
    public RetryContext {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(elapsed, "elapsed must not be null");
    }

    public static RetryContext first() {
        Instant now = Instant.now();
        return new RetryContext(1, now, Duration.ZERO, null);
    }

    public static RetryContext first(Duration budget) {
        Instant now = Instant.now();
        return new RetryContext(1, now, Duration.ZERO, budget);
    }

    public RetryContext next() {
        Duration newElapsed = Duration.between(startedAt, Instant.now());
        Duration remainingBudget = budget == null ? null : budget.minus(newElapsed);
        return new RetryContext(attemptNumber + 1, startedAt, newElapsed, remainingBudget);
    }

    public boolean hasBudgetRemaining() {
        return budget == null || budget.compareTo(elapsed) > 0;
    }

    public Duration remainingBudget() {
        if (budget == null) {
            return null;
        }
        Duration remaining = budget.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
