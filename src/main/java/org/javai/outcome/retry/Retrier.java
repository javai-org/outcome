package org.javai.outcome.retry;

import org.javai.outcome.Failure;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.boundary.ThrowingSupplier;
import org.javai.outcome.ops.OpReporter;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
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

    /**
     * Executes an operation with corrective feedback—the last failure is passed to each retry.
     *
     * <p>This enables self-correcting retries where the operation can adjust based on
     * why the previous attempt failed. Useful for LLM interactions where error context
     * can be fed back into the prompt.
     *
     * <p>Example usage:</p>
     * <pre>{@code
     * Outcome<Order> result = retrier.executeWithFeedback("GenerateOrder", policy, lastFailure -> {
     *     String prompt = buildPrompt(orderId);
     *     if (lastFailure != null) {
     *         prompt += "\nPrevious attempt failed: " + lastFailure.message();
     *     }
     *     return boundary.call("LLM.generate", () -> llm.generate(prompt, Order.class));
     * });
     * }</pre>
     *
     * @param operation The operation name for reporting
     * @param policy The retry policy to apply
     * @param attempt A function that receives the last failure (null on first attempt)
     *                and returns an Outcome
     * @return The final Outcome after success or retry exhaustion
     */
    public <T> Outcome<T> executeWithFeedback(
            String operation,
            RetryPolicy policy,
            Function<Failure, Outcome<T>> attempt
    ) {
        return executeWithFeedback(operation, policy, null, attempt);
    }

    /**
     * Executes an operation with corrective feedback and a time budget.
     *
     * @param operation The operation name for reporting
     * @param policy The retry policy to apply
     * @param budget Maximum time to spend retrying (null for unlimited)
     * @param attempt A function that receives the last failure (null on first attempt)
     *                and returns an Outcome
     * @return The final Outcome after success or retry exhaustion
     */
    public <T> Outcome<T> executeWithFeedback(
            String operation,
            RetryPolicy policy,
            Duration budget,
            Function<Failure, Outcome<T>> attempt
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");

        RetryContext context = budget == null ? RetryContext.first() : RetryContext.first(budget);
        Failure lastFailure = null;
        Outcome<T> result = attempt.apply(null);  // First attempt, no previous failure

        while (result instanceof Outcome.Fail<T> fail) {
            lastFailure = fail.failure();
            RetryDecision decision = policy.decide(context, lastFailure);

            if (decision instanceof RetryDecision.GiveUp) {
                reporter.reportRetryExhausted(lastFailure, context.attemptNumber(), policy.id());
                return result;
            }

            if (decision instanceof RetryDecision.Retry retry) {
                reporter.reportRetryAttempt(lastFailure, context.attemptNumber(), policy.id());
                sleep(retry.delay());
                context = context.next();
                result = attempt.apply(lastFailure);  // Pass failure to next attempt
            }
        }

        return result;
    }

    /**
     * Executes with corrective feedback, using a failure interpreter to transform errors.
     *
     * <p>The failure interpreter allows the caller to transform or enrich the failure
     * before it's passed back to the operation. Return null to retry without feedback.
     *
     * <p>Example usage:</p>
     * <pre>{@code
     * Outcome<Order> result = retrier.executeWithFeedback("GenerateOrder", policy,
     *     feedback -> boundary.call("LLM.generate", () -> llm.generate(prompt + feedback)),
     *     failure -> {
     *         if (failure.code().equals("RATE_LIMITED")) {
     *             return "Please generate a shorter response.";
     *         }
     *         return null;  // No feedback for other errors
     *     }
     * );
     * }</pre>
     *
     * @param operation The operation name for reporting
     * @param policy The retry policy to apply
     * @param attempt A function that receives interpreted feedback (null on first attempt
     *                or when interpreter returns null) and returns an Outcome
     * @param failureInterpreter Transforms a failure into feedback for the next attempt;
     *                           return null for no feedback
     * @return The final Outcome after success or retry exhaustion
     */
    public <T> Outcome<T> executeWithFeedback(
            String operation,
            RetryPolicy policy,
            Function<String, Outcome<T>> attempt,
            Function<Failure, String> failureInterpreter
    ) {
        Objects.requireNonNull(failureInterpreter, "failureInterpreter must not be null");

        return executeWithFeedback(operation, policy, failure -> {
            String feedback = failure == null ? null : failureInterpreter.apply(failure);
            return attempt.apply(feedback);
        });
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

    // === STATIC CONVENIENCE METHODS ===

    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(100);
    private static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(5);

    private static void requireValidAttempts(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0, was: " + maxAttempts);
        }
    }

    /**
     * Silent retry with exponential backoff. Useful for prototyping and testing.
     *
     * <p>Uses a default initial delay of 100ms and max delay of 5 seconds.
     * No failures are reported.
     *
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @param work the work to execute
     * @return the final Outcome after success or retry exhaustion
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static <T> Outcome<T> attempt(
            int maxAttempts,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        return attempt(maxAttempts, DEFAULT_INITIAL_DELAY, work);
    }

    /**
     * Silent retry with exponential backoff and custom initial delay.
     *
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @param initialDelay the initial delay between retries
     * @param work the work to execute
     * @return the final Outcome after success or retry exhaustion
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static <T> Outcome<T> attempt(
            int maxAttempts,
            Duration initialDelay,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        requireValidAttempts(maxAttempts);
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(work, "work must not be null");

        Boundary boundary = Boundary.silent();
        Retrier retrier = new Retrier(OpReporter.noOp());
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                "attempt", maxAttempts, initialDelay, DEFAULT_MAX_DELAY
        );
        return retrier.execute("attempt", policy, boundary, work);
    }

    /**
     * Production retry with reporting and auto-generated exponential backoff policy.
     *
     * @param operation the operation name for reporting
     * @param reporter the reporter for failure notifications
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @param work the work to execute
     * @return the final Outcome after success or retry exhaustion
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static <T> Outcome<T> attempt(
            String operation,
            OpReporter reporter,
            int maxAttempts,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        requireValidAttempts(maxAttempts);
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                operation, maxAttempts, DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY
        );
        return attempt(operation, reporter, policy, work);
    }

    /**
     * Production retry with reporting and custom policy.
     *
     * @param operation the operation name for reporting
     * @param reporter the reporter for failure notifications
     * @param policy the retry policy to apply
     * @param work the work to execute
     * @return the final Outcome after success or retry exhaustion
     */
    public static <T> Outcome<T> attempt(
            String operation,
            OpReporter reporter,
            RetryPolicy policy,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(reporter, "reporter must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(work, "work must not be null");

        Boundary boundary = Boundary.withReporter(reporter);
        Retrier retrier = new Retrier(reporter);
        return retrier.execute(operation, policy, boundary, work);
    }

    /**
     * Single retry attempt (2 total attempts). Silent, for prototyping.
     *
     * @param work the work to execute
     * @return the final Outcome after success or retry exhaustion
     */
    public static <T> Outcome<T> once(ThrowingSupplier<T, ? extends Exception> work) {
        return attempt(2, work);
    }

    /**
     * Silent retry with fixed delay between attempts.
     *
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @param delay the fixed delay between retries
     * @param work the work to execute
     * @return the final Outcome after success or retry exhaustion
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static <T> Outcome<T> withFixedDelay(
            int maxAttempts,
            Duration delay,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        requireValidAttempts(maxAttempts);
        Objects.requireNonNull(delay, "delay must not be null");
        Objects.requireNonNull(work, "work must not be null");

        Boundary boundary = Boundary.silent();
        Retrier retrier = new Retrier(OpReporter.noOp());
        RetryPolicy policy = RetryPolicy.fixed("fixed-delay", maxAttempts, delay);
        return retrier.execute("fixed-delay", policy, boundary, work);
    }

    /**
     * Silent retry with exponential backoff (default delays).
     *
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @param work the work to execute
     * @return the final Outcome after success or retry exhaustion
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static <T> Outcome<T> withBackoff(
            int maxAttempts,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        return attempt(maxAttempts, work);
    }

    // === STATIC CORRECTIVE RETRY METHODS ===

    /**
     * Silent corrective retry—last failure is passed to each retry attempt.
     *
     * <p>Useful for LLM interactions where error context can be fed back into the prompt.
     *
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @param work a function that receives the last failure (null on first attempt)
     *             and returns the work to execute
     * @return the final Outcome after success or retry exhaustion
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static <T> Outcome<T> attemptWithFeedback(
            int maxAttempts,
            Function<Failure, ThrowingSupplier<T, ? extends Exception>> work
    ) {
        requireValidAttempts(maxAttempts);
        Objects.requireNonNull(work, "work must not be null");

        Boundary boundary = Boundary.silent();
        Retrier retrier = new Retrier(OpReporter.noOp());
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                "corrective", maxAttempts, DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY
        );
        return retrier.executeWithFeedback(
                "corrective",
                policy,
                failure -> boundary.call("corrective", work.apply(failure))
        );
    }

    /**
     * Silent corrective retry with a failure interpreter.
     *
     * <p>The interpreter transforms failures into feedback strings. Return null for no feedback.
     *
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @param work a function that receives interpreted feedback (null on first attempt
     *             or when interpreter returns null) and returns the work to execute
     * @param failureInterpreter transforms a failure into feedback; return null for no feedback
     * @return the final Outcome after success or retry exhaustion
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static <T> Outcome<T> attemptWithFeedback(
            int maxAttempts,
            Function<String, ThrowingSupplier<T, ? extends Exception>> work,
            Function<Failure, String> failureInterpreter
    ) {
        requireValidAttempts(maxAttempts);
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(failureInterpreter, "failureInterpreter must not be null");

        Boundary boundary = Boundary.silent();
        Retrier retrier = new Retrier(OpReporter.noOp());
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                "corrective", maxAttempts, DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY
        );
        return retrier.executeWithFeedback(
                "corrective",
                policy,
                feedback -> boundary.call("corrective", work.apply(feedback)),
                failureInterpreter
        );
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
