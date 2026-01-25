package org.javai.outcome.retry;

import org.javai.outcome.*;
import org.javai.outcome.ops.OpReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class RetrierTest {

    private List<Failure> reportedFailures;
    private List<RetryAttempt> reportedRetries;
    private List<RetryExhausted> reportedExhausted;
    private Retrier retrier;

    record RetryAttempt(Failure failure, int attemptNumber, String policyId) {}
    record RetryExhausted(Failure failure, int totalAttempts, String policyId) {}

    @BeforeEach
    void setUp() {
        reportedFailures = new ArrayList<>();
        reportedRetries = new ArrayList<>();
        reportedExhausted = new ArrayList<>();

        OpReporter reporter = new OpReporter() {
            @Override
            public void report(Failure failure) {
                reportedFailures.add(failure);
            }

            @Override
            public void reportRetryAttempt(Failure failure, int attemptNumber, String policyId) {
                reportedRetries.add(new RetryAttempt(failure, attemptNumber, policyId));
            }

            @Override
            public void reportRetryExhausted(Failure failure, int totalAttempts, String policyId) {
                reportedExhausted.add(new RetryExhausted(failure, totalAttempts, policyId));
            }
        };

        // Use a no-op sleeper for fast tests
        retrier = new Retrier(reporter, millis -> {});
    }

    @Test
    void execute_success_returnsOk() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(10));

        Outcome<String> result = retrier.execute("Op", policy, () -> Outcome.ok("success"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success");
        assertThat(reportedRetries).isEmpty();
    }

    @Test
    void execute_retriesOnTransientFailure() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(10));
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = retrier.execute("Op", policy, () -> {
            if (attempts.incrementAndGet() < 3) {
                return Outcome.fail(createTransientFailure("attempt " + attempts.get()));
            }
            return Outcome.ok("success on attempt 3");
        });

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success on attempt 3");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(reportedRetries).hasSize(2);
    }

    @Test
    void execute_givesUpAfterMaxAttempts() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(10));

        Outcome<String> result = retrier.execute("Op", policy,
                () -> Outcome.fail(createTransientFailure("always fails")));

        assertThat(result.isFail()).isTrue();
        assertThat(reportedRetries).hasSize(2); // attempts 1 and 2
        assertThat(reportedExhausted).hasSize(1);
        assertThat(reportedExhausted.getFirst().totalAttempts()).isEqualTo(3);
    }

    @Test
    void execute_doesNotRetryPermanentFailure() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(10));
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = retrier.execute("Op", policy, () -> {
            attempts.incrementAndGet();
            return Outcome.fail(createPermanentFailure("not retryable"));
        });

        assertThat(result.isFail()).isTrue();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(reportedRetries).isEmpty();
        assertThat(reportedExhausted).hasSize(1);
    }

    @Test
    void execute_noRetryPolicy_neverRetries() {
        RetryPolicy policy = RetryPolicy.noRetry();
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = retrier.execute("Op", policy, () -> {
            attempts.incrementAndGet();
            return Outcome.fail(createTransientFailure("failure"));
        });

        assertThat(result.isFail()).isTrue();
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_exponentialBackoff_calculatesDelays() {
        List<Long> sleepTimes = new ArrayList<>();
        Retrier trackingRetrier = new Retrier(OpReporter.noOp(), sleepTimes::add);

        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                "exp-backoff",
                4,
                Duration.ofMillis(100),
                Duration.ofSeconds(1)
        );

        AtomicInteger attempts = new AtomicInteger(0);
        trackingRetrier.execute("Op", policy, () -> {
            if (attempts.incrementAndGet() < 4) {
                return Outcome.fail(createTransientFailure("retry"));
            }
            return Outcome.ok("done");
        });

        assertThat(sleepTimes).containsExactly(100L, 200L, 400L);
    }

    @Test
    void execute_exponentialBackoff_capsAtMaxDelay() {
        List<Long> sleepTimes = new ArrayList<>();
        Retrier trackingRetrier = new Retrier(OpReporter.noOp(), sleepTimes::add);

        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                "exp-backoff",
                5,
                Duration.ofMillis(100),
                Duration.ofMillis(300)  // cap
        );

        AtomicInteger attempts = new AtomicInteger(0);
        trackingRetrier.execute("Op", policy, () -> {
            if (attempts.incrementAndGet() < 5) {
                return Outcome.fail(createTransientFailure("retry"));
            }
            return Outcome.ok("done");
        });

        // 100, 200, 300 (capped), 300 (capped)
        assertThat(sleepTimes).containsExactly(100L, 200L, 300L, 300L);
    }

    @Test
    void execute_respectsFailureMinDelay() {
        List<Long> sleepTimes = new ArrayList<>();
        Retrier trackingRetrier = new Retrier(OpReporter.noOp(), sleepTimes::add);

        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                "exp-backoff",
                3,
                Duration.ofMillis(100),
                Duration.ofSeconds(1)
        );

        AtomicInteger attempts = new AtomicInteger(0);
        trackingRetrier.execute("Op", policy, () -> {
            if (attempts.incrementAndGet() < 3) {
                // Failure with minDelay hint greater than calculated delay
                return Outcome.fail(createTransientFailureWithMinDelay("retry", Duration.ofMillis(500)));
            }
            return Outcome.ok("done");
        });

        // First delay: max(100, 500) = 500, Second delay: max(200, 500) = 500
        assertThat(sleepTimes).containsExactly(500L, 500L);
    }

    private Failure createTransientFailure(String message) {
        FailureKind kind = FailureKind.transientFailure(
                FailureCode.of("test", "transient"),
                message,
                null
        );
        return Failure.of(kind, "TestOp");
    }

    private Failure createTransientFailureWithMinDelay(String message, Duration minDelay) {
        FailureKind kind = new FailureKind(
                FailureCode.of("test", "transient"),
                message,
                FailureCategory.RECOVERABLE,
                FailureStability.TRANSIENT,
                RetryHint.withDelay(minDelay),
                null
        );
        return Failure.of(kind, "TestOp");
    }

    private Failure createPermanentFailure(String message) {
        FailureKind kind = FailureKind.permanentFailure(
                FailureCode.of("test", "permanent"),
                message,
                null
        );
        return Failure.of(kind, "TestOp");
    }

    // === STATIC CONVENIENCE METHOD TESTS ===

    @Test
    void attempt_rejectsZeroAttempts() {
        assertThatThrownBy(() -> Retrier.attempt(0, () -> "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts must be > 0");
    }

    @Test
    void attempt_rejectsNegativeAttempts() {
        assertThatThrownBy(() -> Retrier.attempt(-1, () -> "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts must be > 0");
    }

    @Test
    void attempt_singleAttempt_returnsSuccess() {
        Outcome<String> result = Retrier.attempt(1, () -> "success");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success");
    }

    @Test
    void attempt_retriesOnFailure() {
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.attempt(3, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new Exception("transient error");
            }
            return "success on attempt 3";
        });

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success on attempt 3");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void attempt_withCustomDelay_works() {
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.attempt(2, Duration.ofMillis(1), () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new Exception("transient error");
            }
            return "success";
        });

        assertThat(result.isOk()).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void once_retriesExactlyOnce() {
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.once(() -> {
            attempts.incrementAndGet();
            throw new Exception("always fails");
        });

        assertThat(result.isFail()).isTrue();
        assertThat(attempts.get()).isEqualTo(2);  // 1 original + 1 retry
    }

    @Test
    void withFixedDelay_rejectsZeroAttempts() {
        assertThatThrownBy(() -> Retrier.withFixedDelay(0, Duration.ofMillis(10), () -> "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts must be > 0");
    }

    @Test
    void withFixedDelay_retriesWithFixedDelay() {
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.withFixedDelay(3, Duration.ofMillis(1), () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new Exception("transient error");
            }
            return "success";
        });

        assertThat(result.isOk()).isTrue();
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void withBackoff_rejectsZeroAttempts() {
        assertThatThrownBy(() -> Retrier.withBackoff(0, () -> "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts must be > 0");
    }

    @Test
    void withBackoff_retriesWithBackoff() {
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.withBackoff(3, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new Exception("transient error");
            }
            return "success";
        });

        assertThat(result.isOk()).isTrue();
        assertThat(attempts.get()).isEqualTo(3);
    }

    // === CORRECTIVE RETRY (FEEDBACK) TESTS ===

    @Test
    void executeWithFeedback_passesNullOnFirstAttempt() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(1));
        List<Failure> receivedFailures = new ArrayList<>();

        retrier.executeWithFeedback("Op", policy, lastFailure -> {
            receivedFailures.add(lastFailure);
            return Outcome.ok("success");
        });

        assertThat(receivedFailures).hasSize(1);
        assertThat(receivedFailures.getFirst()).isNull();
    }

    @Test
    void executeWithFeedback_passesLastFailureToRetry() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(1));
        List<Failure> receivedFailures = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        retrier.executeWithFeedback("Op", policy, lastFailure -> {
            receivedFailures.add(lastFailure);
            if (attempts.incrementAndGet() < 3) {
                return Outcome.fail(createTransientFailure("attempt " + attempts.get()));
            }
            return Outcome.ok("success");
        });

        assertThat(receivedFailures).hasSize(3);
        assertThat(receivedFailures.get(0)).isNull();  // First attempt
        assertThat(receivedFailures.get(1).kind().message()).isEqualTo("attempt 1");
        assertThat(receivedFailures.get(2).kind().message()).isEqualTo("attempt 2");
    }

    @Test
    void executeWithFeedback_withInterpreter_transformsFailure() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(1));
        List<String> receivedFeedback = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        retrier.executeWithFeedback("Op", policy,
                feedback -> {
                    receivedFeedback.add(feedback);
                    if (attempts.incrementAndGet() < 3) {
                        return Outcome.fail(createTransientFailure("error " + attempts.get()));
                    }
                    return Outcome.ok("success");
                },
                failure -> "Transformed: " + failure.kind().message()
        );

        assertThat(receivedFeedback).hasSize(3);
        assertThat(receivedFeedback.get(0)).isNull();  // First attempt
        assertThat(receivedFeedback.get(1)).isEqualTo("Transformed: error 1");
        assertThat(receivedFeedback.get(2)).isEqualTo("Transformed: error 2");
    }

    @Test
    void executeWithFeedback_withInterpreter_nullFeedbackMeansNoContext() {
        RetryPolicy policy = RetryPolicy.fixed("test", 3, Duration.ofMillis(1));
        List<String> receivedFeedback = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        retrier.executeWithFeedback("Op", policy,
                feedback -> {
                    receivedFeedback.add(feedback);
                    if (attempts.incrementAndGet() < 3) {
                        return Outcome.fail(createTransientFailure("error"));
                    }
                    return Outcome.ok("success");
                },
                failure -> null  // Always return null - no feedback
        );

        assertThat(receivedFeedback).hasSize(3);
        assertThat(receivedFeedback).containsOnly((String) null);  // All null
    }

    @Test
    void attemptWithFeedback_rejectsZeroAttempts() {
        assertThatThrownBy(() -> Retrier.attemptWithFeedback(0, failure -> () -> "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts must be > 0");
    }

    @Test
    void attemptWithFeedback_passesFailureToRetry() {
        List<Failure> receivedFailures = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.attemptWithFeedback(3, lastFailure -> {
            receivedFailures.add(lastFailure);
            return () -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new Exception("error " + attempts.get());
                }
                return "success";
            };
        });

        assertThat(result.isOk()).isTrue();
        assertThat(receivedFailures).hasSize(3);
        assertThat(receivedFailures.get(0)).isNull();
        assertThat(receivedFailures.get(1)).isNotNull();
        assertThat(receivedFailures.get(2)).isNotNull();
    }

    @Test
    void attemptWithFeedback_withInterpreter_transformsFailure() {
        List<String> receivedFeedback = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.attemptWithFeedback(3,
                feedback -> {
                    receivedFeedback.add(feedback);
                    return () -> {
                        if (attempts.incrementAndGet() < 3) {
                            throw new Exception("error " + attempts.get());
                        }
                        return "success";
                    };
                },
                failure -> "Interpreted: " + failure.kind().message()
        );

        assertThat(result.isOk()).isTrue();
        assertThat(receivedFeedback.get(0)).isNull();
        assertThat(receivedFeedback.get(1)).startsWith("Interpreted:");
        assertThat(receivedFeedback.get(2)).startsWith("Interpreted:");
    }
}
