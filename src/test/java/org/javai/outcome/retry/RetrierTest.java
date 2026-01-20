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
        FailureKind kind = FailureKind.transientOp(
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
                FailureCategory.OPERATIONAL,
                FailureStability.TRANSIENT,
                RetryHint.withDelay(minDelay),
                null
        );
        return Failure.of(kind, "TestOp");
    }

    private Failure createPermanentFailure(String message) {
        FailureKind kind = FailureKind.permanentOp(
                FailureCode.of("test", "permanent"),
                message,
                null
        );
        return Failure.of(kind, "TestOp");
    }
}
