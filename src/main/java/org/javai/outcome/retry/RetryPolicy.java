package org.javai.outcome.retry;

import org.javai.outcome.Failure;
import org.javai.outcome.FailureType;

import java.time.Duration;
import java.util.Objects;

/**
 * Decides whether and when to retry after a failure.
 */
public interface RetryPolicy {

    /**
     * A unique identifier for this policy, used in reporting.
     */
    String id();

    /**
     * Evaluates a failure and decides whether to retry.
     *
     * @param context The current retry context
     * @param failure The failure that occurred
     * @return Retry with a delay, or GiveUp
     */
    RetryDecision decide(RetryContext context, Failure failure);

    /**
     * Creates a policy that never retries.
     */
    static RetryPolicy noRetry() {
        return new RetryPolicy() {
            @Override
            public String id() {
                return "no-retry";
            }

            @Override
            public RetryDecision decide(RetryContext context, Failure failure) {
                return new RetryDecision.GiveUp("no-retry policy");
            }
        };
    }

    /**
     * Creates a simple policy with zero delay and max attempts.
     */
    static RetryPolicy immediate(String id, int maxAttempts) {
        return fixed(id, maxAttempts, Duration.ZERO);
    }

    /**
     * Creates a simple policy with fixed delay and max attempts.
     */
    static RetryPolicy fixed(String id, int maxAttempts, Duration delay) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(delay);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }

        return new RetryPolicy() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public RetryDecision decide(RetryContext context, Failure failure) {
                if (failure.type() != FailureType.TRANSIENT) {
                    return RetryDecision.GiveUp.because("failure is not retryable");
                }
                if (context.attemptNumber() >= maxAttempts) {
                    return RetryDecision.GiveUp.because("max attempts reached");
                }
                if (!context.hasBudgetRemaining()) {
                    return RetryDecision.GiveUp.because("budget exhausted");
                }
                return RetryDecision.Retry.after(delay);
            }
        };
    }

    /**
     * Creates a policy with exponential backoff.
     */
    static RetryPolicy exponentialBackoff(String id, int maxAttempts, Duration initialDelay, Duration maxDelay) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(initialDelay);
        Objects.requireNonNull(maxDelay);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }

        return new RetryPolicy() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public RetryDecision decide(RetryContext context, Failure failure) {
                if (failure.type() != FailureType.TRANSIENT) {
                    return RetryDecision.GiveUp.because("failure is not retryable");
                }
                if (context.attemptNumber() >= maxAttempts) {
                    return RetryDecision.GiveUp.because("max attempts reached");
                }
                if (!context.hasBudgetRemaining()) {
                    return RetryDecision.GiveUp.because("budget exhausted");
                }

                // Calculate delay: initialDelay * 2^(attempt-1), capped at maxDelay
                long multiplier = 1L << (context.attemptNumber() - 1);
                Duration delay = initialDelay.multipliedBy(multiplier);
                if (delay.compareTo(maxDelay) > 0) {
                    delay = maxDelay;
                }

                // Respect failure's retryAfter hint
                Duration hintDelay = failure.retryAfter();
                if (hintDelay != null && hintDelay.compareTo(delay) > 0) {
                    delay = hintDelay;
                }

                return RetryDecision.Retry.after(delay);
            }
        };
    }
}
