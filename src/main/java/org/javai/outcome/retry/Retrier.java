package org.javai.outcome.retry;

import org.javai.outcome.Failure;
import org.javai.outcome.FailureId;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.boundary.FailureClassifier;
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
 * Retrier retrier = Retrier.builder()
 *     .policy(RetryPolicy.exponentialBackoff("api-call", 3, Duration.ofMillis(100), Duration.ofSeconds(5)))
 *     .reporter(reporter)
 *     .build();
 *
 * Outcome<Response> result = retrier.execute(
 *     "FetchUser",
 *     () -> boundary.call("UserApi.fetch", () -> userApi.fetch(userId))
 * );
 * }</pre>
 */
public final class Retrier {

    private final RetryPolicy policy;
    private final OpReporter reporter;
    private final Duration budget;
    private final Sleeper sleeper;

    private Retrier(RetryPolicy policy, OpReporter reporter, Duration budget, Sleeper sleeper) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
        this.budget = budget;  // null means unlimited
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    /**
     * Creates a builder for configuring a Retrier instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for configuring a Retrier instance.
     *
     * <p>Example usage:</p>
     * <pre>{@code
     * Retrier retrier = Retrier.builder()
     *     .policy(RetryPolicy.exponentialBackoff("api", 3, Duration.ofMillis(100), Duration.ofSeconds(5)))
     *     .reporter(customReporter)
     *     .budget(Duration.ofSeconds(30))
     *     .build();
     * }</pre>
     */
    public static final class Builder {
        private RetryPolicy policy;
        private OpReporter reporter = OpReporter.noOp();
        private Duration budget;
        private Sleeper sleeper = Thread::sleep;

        private Builder() {}

        /**
         * Sets the retry policy (required).
         *
         * @param policy the retry policy to use
         * @return this builder
         */
        public Builder policy(RetryPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy must not be null");
            return this;
        }

        /**
         * Sets the reporter for retry events (optional, defaults to no-op).
         *
         * @param reporter the reporter for retry events
         * @return this builder
         */
        public Builder reporter(OpReporter reporter) {
            this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
            return this;
        }

        /**
         * Sets a time budget for retry operations (optional, defaults to unlimited).
         *
         * @param budget maximum time to spend retrying
         * @return this builder
         */
        public Builder budget(Duration budget) {
            this.budget = Objects.requireNonNull(budget, "budget must not be null");
            return this;
        }

        /**
         * Sets the sleeper for testing (package-private).
         */
        Builder sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
            return this;
        }

        /**
         * Builds the Retrier instance.
         *
         * @return a configured Retrier
         * @throws NullPointerException if policy has not been set
         */
        public Retrier build() {
            Objects.requireNonNull(policy, "policy must be set");
            return new Retrier(policy, reporter, budget, sleeper);
        }
    }

    /**
     * Executes an operation with retry according to the configured policy.
     *
     * @param operation The operation name for reporting
     * @param attempt A supplier that returns an Outcome
     * @return The final Outcome after retries are exhausted or success
     */
    public <T> Outcome<T> execute(String operation, Supplier<Outcome<T>> attempt) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");

        RetryContext context = budget == null ? RetryContext.first() : RetryContext.first(budget);
        Outcome<T> result = attempt.get();

        while (result instanceof Outcome.Fail<T>(Failure failure)) {
			RetryDecision decision = policy.decide(context, failure);

            if (decision instanceof RetryDecision.GiveUp) {
                reporter.reportRetryExhausted(failure, context.attemptNumber(), policy.id());
                return result;
            }

            if (decision instanceof RetryDecision.Retry(Duration delay)) {
                reporter.reportRetryAttempt(failure, context.attemptNumber(), policy.id());
                sleep(delay);
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
            Boundary boundary,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        return execute(operation, () -> boundary.call(operation, work));
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

    /**
     * A classifier that treats all exceptions as TRANSIENT, enabling retry.
     * Used by convenience methods where we want exceptions to be retried.
     */
    private static final FailureClassifier ALWAYS_TRANSIENT_CLASSIFIER =
            (operation, throwable) -> Failure.transientFailure(
                    FailureId.of("retry", throwable.getClass().getSimpleName()),
                    throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getName(),
                    operation,
                    throwable
            );

    private static void requireValidAttempts(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0, was: " + maxAttempts);
        }
    }

    /**
     * Simple retry with exponential backoff.
     *
     * <p>Uses default delays (100ms initial, 5s max). For custom delays or reporting,
     * use the builder with {@link #builder()}.
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
        requireValidAttempts(maxAttempts);
        Objects.requireNonNull(work, "work must not be null");

        Boundary boundary = Boundary.of(ALWAYS_TRANSIENT_CLASSIFIER, OpReporter.noOp());
        Retrier retrier = Retrier.builder()
                .policy(RetryPolicy.exponentialBackoff("attempt", maxAttempts, DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY))
                .build();
        return retrier.execute("attempt", boundary, work);
    }

    // === GUIDED RETRY BUILDER ===

    /**
     * Creates a builder for guided retry—where failures produce guidance for subsequent attempts.
     *
     * <p>This pattern is useful for LLM interactions where error context can be fed back
     * into the prompt to help the model self-correct.
     *
     * <p>Example usage:</p>
     * <pre>{@code
     * // Simple usage with defaults
     * Outcome<Order> result = Retrier.withGuidance(4)
     *     .attempt(() -> parse(llm.chat(request)))
     *     .deriveGuidance(failure -> "\n\nError: " + failure.message())
     *     .reattempt(guidance -> () -> parse(llm.chat(request + guidance)))
     *     .execute();
     *
     * // With custom policy and reporter
     * Outcome<Order> result = Retrier.withGuidance(4)
     *     .policy(RetryPolicy.fixed("llm", 4, Duration.ofSeconds(1)))
     *     .reporter(customReporter)
     *     .attempt(() -> parse(llm.chat(request)))
     *     .deriveGuidance(failure -> "\n\nError: " + failure.message())
     *     .reattempt(guidance -> () -> parse(llm.chat(request + guidance)))
     *     .execute();
     * }</pre>
     *
     * @param maxAttempts maximum number of attempts (must be > 0)
     * @return a builder for configuring guided retry
     * @throws IllegalArgumentException if maxAttempts is not positive
     */
    public static GuidedRetryBuilder.Initial withGuidance(int maxAttempts) {
        requireValidAttempts(maxAttempts);
        return new GuidedRetryBuilder.Initial(maxAttempts);
    }

    /**
     * Builder for guided retry operations.
     *
     * <p>Configure in order:
     * <ol>
     *   <li>{@link Initial#policy(RetryPolicy)} - optional custom policy</li>
     *   <li>{@link Initial#reporter(OpReporter)} - optional custom reporter</li>
     *   <li>{@link Initial#attempt(ThrowingSupplier)} - the initial work</li>
     *   <li>{@link WithAttempt#deriveGuidance(Function)} - how to convert failures to guidance</li>
     *   <li>{@link WithGuidance#reattempt(Function)} - the work to do with guidance on retries</li>
     *   <li>{@link Complete#execute()} - run the retry operation</li>
     * </ol>
     */
    public static final class GuidedRetryBuilder {
        private GuidedRetryBuilder() {}

        /** Initial builder state - optionally set policy/reporter, then call {@link #attempt}. */
        public static final class Initial {
            private final int maxAttempts;
            private RetryPolicy policy;
            private OpReporter reporter = OpReporter.noOp();

            private Initial(int maxAttempts) {
                this.maxAttempts = maxAttempts;
            }

            /**
             * Sets a custom retry policy (optional).
             *
             * @param policy the retry policy to use
             * @return this builder
             */
            public Initial policy(RetryPolicy policy) {
                this.policy = Objects.requireNonNull(policy, "policy must not be null");
                return this;
            }

            /**
             * Sets a custom reporter (optional).
             *
             * @param reporter the reporter for retry events
             * @return this builder
             */
            public Initial reporter(OpReporter reporter) {
                this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
                return this;
            }

            /**
             * Sets the work to execute on the first attempt.
             *
             * @param work the initial work
             * @param <T> the type of successful result
             * @return the next builder stage
             */
            public <T> WithAttempt<T> attempt(ThrowingSupplier<T, ? extends Exception> work) {
                Objects.requireNonNull(work, "work must not be null");
                RetryPolicy effectivePolicy = policy != null ? policy :
                        RetryPolicy.exponentialBackoff("guided", maxAttempts, DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY);
                return new WithAttempt<>(effectivePolicy, reporter, work);
            }
        }

        /** Builder state after attempt is set - call {@link #deriveGuidance} next. */
        public static final class WithAttempt<T> {
            private final RetryPolicy policy;
            private final OpReporter reporter;
            private final ThrowingSupplier<T, ? extends Exception> initialWork;

            private WithAttempt(RetryPolicy policy, OpReporter reporter,
                               ThrowingSupplier<T, ? extends Exception> initialWork) {
                this.policy = policy;
                this.reporter = reporter;
                this.initialWork = initialWork;
            }

            /**
             * Sets how to derive guidance from a failure.
             *
             * @param deriver function that converts a failure to guidance
             * @return the next builder stage
             */
            public WithGuidance<T> deriveGuidance(Function<Failure, String> deriver) {
                Objects.requireNonNull(deriver, "deriver must not be null");
                return new WithGuidance<>(policy, reporter, initialWork, deriver);
            }
        }

        /** Builder state after deriveGuidance is set - call {@link #reattempt} next. */
        public static final class WithGuidance<T> {
            private final RetryPolicy policy;
            private final OpReporter reporter;
            private final ThrowingSupplier<T, ? extends Exception> initialWork;
            private final Function<Failure, String> guidanceDeriver;

            private WithGuidance(RetryPolicy policy, OpReporter reporter,
                                ThrowingSupplier<T, ? extends Exception> initialWork,
                                Function<Failure, String> guidanceDeriver) {
                this.policy = policy;
                this.reporter = reporter;
                this.initialWork = initialWork;
                this.guidanceDeriver = guidanceDeriver;
            }

            /**
             * Sets the work to execute on retry attempts, given guidance from the previous failure.
             *
             * @param work function that takes guidance and returns the work to execute
             * @return the next builder stage
             */
            public Complete<T> reattempt(Function<String, ThrowingSupplier<T, ? extends Exception>> work) {
                Objects.requireNonNull(work, "work must not be null");
                return new Complete<>(policy, reporter, initialWork, guidanceDeriver, work);
            }
        }

        /** Builder state ready to execute. */
        public static final class Complete<T> {
            private final RetryPolicy policy;
            private final OpReporter reporter;
            private final ThrowingSupplier<T, ? extends Exception> initialWork;
            private final Function<Failure, String> guidanceDeriver;
            private final Function<String, ThrowingSupplier<T, ? extends Exception>> reattemptWork;

            private Complete(RetryPolicy policy, OpReporter reporter,
                            ThrowingSupplier<T, ? extends Exception> initialWork,
                            Function<Failure, String> guidanceDeriver,
                            Function<String, ThrowingSupplier<T, ? extends Exception>> reattemptWork) {
                this.policy = policy;
                this.reporter = reporter;
                this.initialWork = initialWork;
                this.guidanceDeriver = guidanceDeriver;
                this.reattemptWork = reattemptWork;
            }

            /**
             * Executes the guided retry operation.
             *
             * @return the final Outcome after success or retry exhaustion
             */
            public Outcome<T> execute() {
                Boundary boundary = Boundary.of(ALWAYS_TRANSIENT_CLASSIFIER, reporter);

                RetryContext context = RetryContext.first();
                Outcome<T> result = boundary.call("guided", initialWork);

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

                        String guidance = guidanceDeriver.apply(failure);
                        result = boundary.call("guided", reattemptWork.apply(guidance));
                    }
                }

                return result;
            }

            private void sleep(Duration duration) {
                if (duration.isZero() || duration.isNegative()) {
                    return;
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
