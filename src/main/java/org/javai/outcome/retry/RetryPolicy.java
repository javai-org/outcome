package org.javai.outcome.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import org.javai.outcome.Failure;
import org.javai.outcome.FailureType;

/**
 * Decides whether and when to retry after a failure.
 */
public interface RetryPolicy {

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
        return (context, failure) -> new RetryDecision.GiveUp("no-retry policy");
    }

    /**
     * Creates a simple policy with zero delay and max attempts.
     */
    static RetryPolicy immediate(int maxAttempts) {
        return fixed(maxAttempts, Duration.ZERO);
    }

    /**
     * Creates a simple policy with fixed delay and max attempts.
     */
    static RetryPolicy fixed(int maxAttempts, Duration delay) {
        Objects.requireNonNull(delay);
        return withDelayStrategy(maxAttempts, (context, failure) -> delay);
    }

    /**
     * Creates a policy with exponential backoff.
     */
    static RetryPolicy backoff(int maxAttempts, Duration initialDelay, Duration maxDelay) {
        Objects.requireNonNull(initialDelay);
        Objects.requireNonNull(maxDelay);
        return withDelayStrategy(maxAttempts, (context, failure) -> {
            long multiplier = 1L << (context.attemptNumber() - 1);
            Duration calculatedDelay = initialDelay.multipliedBy(multiplier);
            return calculatedDelay.compareTo(maxDelay) > 0 ? maxDelay : calculatedDelay;
        });
    }

    /**
     * Creates a policy from a delay strategy, adding common guards and retry hint support.
     */
    private static RetryPolicy withDelayStrategy(int maxAttempts,
            BiFunction<RetryContext, Failure, Duration> delayStrategy) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        return (context, failure) -> {
            if (failure.type() != FailureType.TRANSIENT) {
                return RetryDecision.GiveUp.because("failure is not retryable");
            }
            if (context.attemptNumber() >= maxAttempts) {
                return RetryDecision.GiveUp.because("max attempts reached");
            }
            if (!context.hasBudgetRemaining()) {
                return RetryDecision.GiveUp.because("budget exhausted");
            }

            Duration baseDelay = delayStrategy.apply(context, failure);

            // Respect failure's retryAfter hint
            Duration effectiveDelay = failure.retryAfter()
                    .filter(hint -> hint.compareTo(baseDelay) > 0)
                    .orElse(baseDelay);

            return RetryDecision.Retry.after(effectiveDelay);
        };
    }
}
