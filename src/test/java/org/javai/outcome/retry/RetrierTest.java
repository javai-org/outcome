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
    private OpReporter reporter;

    record RetryAttempt(Failure failure, int attemptNumber) {}
    record RetryExhausted(Failure failure, int totalAttempts) {}

    @BeforeEach
    void setUp() {
        reportedFailures = new ArrayList<>();
        reportedRetries = new ArrayList<>();
        reportedExhausted = new ArrayList<>();

        reporter = new OpReporter() {
            @Override
            public void report(Failure failure) {
                reportedFailures.add(failure);
            }

            @Override
            public void reportRetryAttempt(Failure failure, int attemptNumber) {
                reportedRetries.add(new RetryAttempt(failure, attemptNumber));
            }

            @Override
            public void reportRetryExhausted(Failure failure, int totalAttempts) {
                reportedExhausted.add(new RetryExhausted(failure, totalAttempts));
            }
        };
    }

    private Retrier retrierWith(RetryPolicy policy) {
        return Retrier.builder()
                .policy(policy)
                .reporter(reporter)
                .sleeper(millis -> {})  // No-op for fast tests
                .build();
    }

    @Test
    void execute_immediate_success_returnsOk() {
        RetryPolicy policy = RetryPolicy.immediate(3);
        Retrier retrier = retrierWith(policy);

        Outcome<String> result = retrier.execute( () -> Outcome.ok("success"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success");
        assertThat(reportedRetries).isEmpty();
    }

    @Test
    void execute_success_returnsOk() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofMillis(10));
        Retrier retrier = retrierWith(policy);

        Outcome<String> result = retrier.execute( () -> Outcome.ok("success"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success");
        assertThat(reportedRetries).isEmpty();
    }

    @Test
    void execute_retriesOnTransientFailure() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofMillis(10));
        Retrier retrier = retrierWith(policy);
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = retrier.execute( () -> {
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
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofMillis(10));
        Retrier retrier = retrierWith(policy);

        Outcome<String> result = retrier.execute(
                () -> Outcome.fail(createTransientFailure("always fails")));

        assertThat(result.isFail()).isTrue();
        assertThat(reportedRetries).hasSize(2); // attempts 1 and 2
        assertThat(reportedExhausted).hasSize(1);
        assertThat(reportedExhausted.getFirst().totalAttempts()).isEqualTo(3);
    }

    @Test
    void execute_doesNotRetryPermanentFailure() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofMillis(10));
        Retrier retrier = retrierWith(policy);
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = retrier.execute( () -> {
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
        Retrier retrier = retrierWith(RetryPolicy.noRetry());
        AtomicInteger attempts = new AtomicInteger(0);

        Outcome<String> result = retrier.execute( () -> {
            attempts.incrementAndGet();
            return Outcome.fail(createTransientFailure("failure"));
        });

        assertThat(result.isFail()).isTrue();
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_exponentialBackoff_calculatesDelays() {
        List<Long> sleepTimes = new ArrayList<>();
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                4, Duration.ofMillis(100), Duration.ofSeconds(1)
        );
        Retrier retrier = Retrier.builder()
                .policy(policy)
                .sleeper(sleepTimes::add)
                .build();

        AtomicInteger attempts = new AtomicInteger(0);
        retrier.execute( () -> {
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
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                5, Duration.ofMillis(100), Duration.ofMillis(300)
        );
        Retrier retrier = Retrier.builder()
                .policy(policy)
                .sleeper(sleepTimes::add)
                .build();

        AtomicInteger attempts = new AtomicInteger(0);
        retrier.execute( () -> {
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
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
                3, Duration.ofMillis(100), Duration.ofSeconds(1)
        );
        Retrier retrier = Retrier.builder()
                .policy(policy)
                .sleeper(sleepTimes::add)
                .build();

        AtomicInteger attempts = new AtomicInteger(0);
        retrier.execute( () -> {
            if (attempts.incrementAndGet() < 3) {
                // Failure with retryAfter hint greater than calculated delay
                return Outcome.fail(createTransientFailureWithRetryAfter("retry", Duration.ofMillis(500)));
            }
            return Outcome.ok("done");
        });

        // First delay: max(100, 500) = 500, Second delay: max(200, 500) = 500
        assertThat(sleepTimes).containsExactly(500L, 500L);
    }

    private Failure createTransientFailure(String message) {
        return Failure.transientFailure(
                FailureId.of("test", "transient"),
                message,
                "TestOp",
                null
        );
    }

    private Failure createTransientFailureWithRetryAfter(String message, Duration retryAfter) {
        return Failure.transientFailure(
                FailureId.of("test", "transient"),
                message,
                "TestOp",
                null,
                retryAfter
        );
    }

    private Failure createPermanentFailure(String message) {
        return Failure.permanentFailure(
                FailureId.of("test", "permanent"),
                message,
                "TestOp",
                null
        );
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

    // === BUILDER TESTS ===

    @Test
    void builder_requiresPolicy() {
        assertThatThrownBy(() -> Retrier.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("policy must be set");
    }

    @Test
    void builder_allowsCustomReporter() {
        List<Failure> reported = new ArrayList<>();
        OpReporter customReporter = new OpReporter() {
            @Override
            public void report(Failure failure) {
                reported.add(failure);
            }

            @Override
            public void reportRetryExhausted(Failure failure, int totalAttempts) {
                reported.add(failure);
            }
        };

        Retrier retrier = Retrier.builder()
                .policy(RetryPolicy.fixed(1, Duration.ofMillis(1)))
                .reporter(customReporter)
                .sleeper(millis -> {})
                .build();

        retrier.execute( () -> Outcome.fail(createTransientFailure("error")));

        assertThat(reported).hasSize(1);
    }

    @Test
    void builder_allowsBudget() {
        Retrier retrier = Retrier.builder()
                .policy(RetryPolicy.fixed(100, Duration.ofMillis(1)))
                .budget(Duration.ofMillis(10))
                .sleeper(millis -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Outcome<String> result = retrier.execute( () -> {
            attempts.incrementAndGet();
            return Outcome.fail(createTransientFailure("always fails"));
        });

        // Should give up due to budget exhaustion, not max attempts
        assertThat(result.isFail()).isTrue();
        assertThat(attempts.get()).isLessThan(100);
    }

    // === GUIDED RETRY BUILDER TESTS ===

    @Test
    void withGuidance_rejectsZeroAttempts() {
        assertThatThrownBy(() -> Retrier.withGuidance(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts must be > 0");
    }

    // Note: The type-state builder pattern enforces method order at compile time.
    // You cannot call deriveGuidance() before attempt(), or reattempt() before deriveGuidance().
    // This is better than runtime checks - invalid sequences simply won't compile.

    @Test
    void withGuidance_successOnFirstAttempt() {
        AtomicInteger attemptCalls = new AtomicInteger(0);
        AtomicInteger reattemptCalls = new AtomicInteger(0);

        Outcome<String> result = Retrier.withGuidance(3)
                .attempt(() -> {
                    attemptCalls.incrementAndGet();
                    return "success";
                })
                .deriveGuidance(f -> "guidance")
                .reattempt(g -> () -> {
                    reattemptCalls.incrementAndGet();
                    return "reattempted";
                })
                .execute();

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success");
        assertThat(attemptCalls.get()).isEqualTo(1);
        assertThat(reattemptCalls.get()).isEqualTo(0);
    }

    @Test
    void withGuidance_usesAttemptFirst_thenReattemptWithGuidance() {
        AtomicInteger attemptCalls = new AtomicInteger(0);
        AtomicInteger reattemptCalls = new AtomicInteger(0);
        List<String> receivedGuidance = new ArrayList<>();

        Outcome<String> result = Retrier.withGuidance(4)
                .<String>attempt(() -> {
                    attemptCalls.incrementAndGet();
                    throw new Exception("initial failed");
                })
                .deriveGuidance(f -> "Fix: " + f.message())
                .reattempt(guidance -> {
                    receivedGuidance.add(guidance);
                    return () -> {
                        int count = reattemptCalls.incrementAndGet();
                        if (count < 2) {
                            throw new Exception("reattempt " + count + " failed");
                        }
                        return "success after reattempts";
                    };
                })
                .execute();

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success after reattempts");
        assertThat(attemptCalls.get()).isEqualTo(1);
        assertThat(reattemptCalls.get()).isEqualTo(2);
        assertThat(receivedGuidance).hasSize(2);
        assertThat(receivedGuidance.get(0)).isEqualTo("Fix: initial failed");
        assertThat(receivedGuidance.get(1)).isEqualTo("Fix: reattempt 1 failed");
    }

    @Test
    void withGuidance_failsAfterMaxAttempts() {
        AtomicInteger totalAttempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.withGuidance(3)
                .<String>attempt(() -> {
                    totalAttempts.incrementAndGet();
                    throw new Exception("always fails");
                })
                .deriveGuidance(f -> "try again")
                .reattempt(g -> () -> {
                    totalAttempts.incrementAndGet();
                    throw new Exception("still fails");
                })
                .execute();

        assertThat(result.isFail()).isTrue();
        assertThat(totalAttempts.get()).isEqualTo(3);
    }

    @Test
    void withGuidance_acceptsCustomPolicyAndReporter() {
        List<String> retryEvents = new ArrayList<>();
        OpReporter customReporter = new OpReporter() {
            @Override
            public void report(Failure failure) {
                retryEvents.add("report:" + failure.message());
            }

            @Override
            public void reportRetryAttempt(Failure failure, int attemptNumber) {
                retryEvents.add("retry:" + attemptNumber);
            }
        };

        AtomicInteger reattempts = new AtomicInteger(0);

        Outcome<String> result = Retrier.withGuidance(3)
                .policy(RetryPolicy.fixed(3, Duration.ZERO))
                .reporter(customReporter)
                .<String>attempt(() -> {
                    throw new Exception("first attempt failed");
                })
                .deriveGuidance(f -> "guidance: " + f.message())
                .reattempt(g -> () -> {
                    if (reattempts.incrementAndGet() < 2) {
                        throw new Exception("reattempt failed");
                    }
                    return "success with guidance";
                })
                .execute();

        assertThat(result.isOk()).isTrue();
        // Custom reporter was called
        assertThat(retryEvents).contains("retry:1", "retry:2");
    }
}
