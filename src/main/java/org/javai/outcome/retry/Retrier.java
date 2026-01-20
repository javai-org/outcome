package org.javai.outcome.retry;

import org.javai.outcome.Failure;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.boundary.ThrowingSupplier;
import org.javai.outcome.ops.OpReporter;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Executes operations with retry logic based on policies.
 * Operates entirely over Outcome values—no exceptions escape.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Retrier retrier = new Retrier(reporter);
 * RetryPolicy policy = RetryPolicy.exponentialBackoff("api-call", 3, Duration.ofMillis(100), Duration.ofSeconds(5));
 *
 * Outcome<Response> result = retrier.execute(
 *     "FetchUser",
 *     policy,
 *     () -> boundary.call("UserApi.fetch", () -> userApi.fetch(userId))
 * );
 * }</pre>
 */
public final class Retrier {

    private final OpReporter reporter;
    private final Sleeper sleeper;

    public Retrier(OpReporter reporter) {
        this(reporter, Thread::sleep);
    }

    Retrier(OpReporter reporter, Sleeper sleeper) {
        this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    /**
     * Executes an operation with retry according to the given policy.
     *
     * @param operation The operation name for reporting
     * @param policy The retry policy to apply
     * @param attempt A supplier that returns an Outcome
     * @return The final Outcome after retries are exhausted or success
     */
    public <T> Outcome<T> execute(String operation, RetryPolicy policy, Supplier<Outcome<T>> attempt) {
        return execute(operation, policy, null, attempt);
    }

    /**
     * Executes an operation with retry and a time budget.
     *
     * @param operation The operation name for reporting
     * @param policy The retry policy to apply
     * @param budget Maximum time to spend retrying (null for unlimited)
     * @param attempt A supplier that returns an Outcome
     * @return The final Outcome after retries are exhausted or success
     */
    public <T> Outcome<T> execute(String operation, RetryPolicy policy, Duration budget, Supplier<Outcome<T>> attempt) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");

        RetryContext context = budget == null ? RetryContext.first() : RetryContext.first(budget);
        Outcome<T> result = attempt.get();

        while (result instanceof Outcome.Fail<T> fail) {
            Failure failure = fail.failure();
            RetryDecision decision = policy.decide(context, failure);

            if (decision instanceof RetryDecision.GiveUp) {
                reporter.reportRetryExhausted(failure, context.attemptNumber(), policy.id());
                return result;
            }

            if (decision instanceof RetryDecision.Retry retry) {
                reporter.reportRetryAttempt(failure, context.attemptNumber(), policy.id());
                sleep(retry.delay());
                context = context.next();
                result = attempt.get();
            }
        }

        return result;
    }

    /**
     * Convenience method that wraps a throwing supplier with a Boundary before retrying.
     */
    public <T> Outcome<T> execute(
            String operation,
            RetryPolicy policy,
            Boundary boundary,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        return execute(operation, policy, () -> boundary.call(operation, work));
    }

    private void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
