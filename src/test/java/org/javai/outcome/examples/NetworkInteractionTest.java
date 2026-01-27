package org.javai.outcome.examples;

import org.javai.outcome.*;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.boundary.BoundaryFailureClassifier;
import org.javai.outcome.ops.OpReporter;
import org.javai.outcome.retry.Retrier;
import org.javai.outcome.retry.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates the Outcome framework in a realistic network interaction scenario.
 */
public class NetworkInteractionTest {

    private Boundary boundary;
    private Retrier retrier;
    private List<Failure> reportedFailures;

    @BeforeEach
    void setUp() {
        reportedFailures = new ArrayList<>();

        OpReporter reporter = new OpReporter() {
            @Override
            public void report(Failure failure) {
                reportedFailures.add(failure);
                System.out.println("[REPORTED] " + failure.id() + ": " + failure.message());
            }

            @Override
            public void reportRetryAttempt(Failure failure, int attemptNumber, String policyId) {
                System.out.println("[RETRY] Attempt " + attemptNumber + " for " + failure.operation());
            }

            @Override
            public void reportRetryExhausted(Failure failure, int totalAttempts, String policyId) {
                System.out.println("[EXHAUSTED] Gave up after " + totalAttempts + " attempts");
            }
        };

        boundary = new Boundary(new BoundaryFailureClassifier(), reporter);
        retrier = new Retrier(reporter);
    }

    @Test
    void successfulNetworkCall_returnsOk() {
        // Simulate a successful API call
        Outcome<String> outcome = boundary.call("UserApi.fetchUser", () ->
            "{\"id\": 123, \"name\": \"Alice\"}"
        );

        // Pattern matching on the outcome
        String result = switch (outcome) {
            case Outcome.Ok<String> ok -> {
                System.out.println("Got user: " + ok.value());
                yield ok.value();
            }
            case Outcome.Fail<String> fail -> {
                System.out.println("Failed: " + fail.failure().message());
                yield "default";
            }
        };

        assertThat(result).contains("Alice");
        assertThat(reportedFailures).isEmpty();
    }

    @Test
    void failedNetworkCall_returnsFail() {
        // Simulate a network timeout
        Outcome<String> outcome = boundary.call("UserApi.fetchUser", () -> {
            throw new HttpConnectTimeoutException("Connection timed out after 30s");
        });

        assertThat(outcome.isFail()).isTrue();

        // Extract failure details
        Failure failure = ((Outcome.Fail<String>) outcome).failure();
        assertThat(failure.id()).isEqualTo(FailureId.of("network", "http_timeout"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);

        // Failure was reported
        assertThat(reportedFailures).hasSize(1);
    }

    @Test
    void networkCallWithRetry_succeedsOnThirdAttempt() {
        // Use zero delays for fast tests
        RetryPolicy policy = RetryPolicy.fixed("api-retry", 5, Duration.ZERO);

        var attempts = new int[]{0};

        Outcome<String> outcome = retrier.execute("UserApi.fetchUser", policy, () ->
                boundary.call("UserApi.fetchUser", () -> {
                    attempts[0]++;
                    if (attempts[0] < 3) {
                        throw new HttpConnectTimeoutException("Timeout on attempt " + attempts[0]);
                    }
                    return "{\"id\": 123, \"name\": \"Bob\"}";
                })
        );

        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.getOrThrow()).contains("Bob");
        assertThat(attempts[0]).isEqualTo(3);
    }

    @Test
    void networkCallWithRetry_givesUpAfterMaxAttempts() {
        RetryPolicy policy = RetryPolicy.fixed("api-retry", 3, Duration.ZERO);

        Outcome<String> outcome = retrier.execute("UserApi.fetchUser", policy, () ->
                boundary.call("UserApi.fetchUser", () -> {
                    throw new HttpConnectTimeoutException("Always times out");
                })
        );

        assertThat(outcome.isFail()).isTrue();
        // 3 failures reported (one per attempt)
        assertThat(reportedFailures).hasSize(3);
    }

    @Test
    void chainingOutcomes_withFlatMap() {
        // Simulate fetching a user, then fetching their orders
        Outcome<String> userOutcome = boundary.call("UserApi.fetchUser",
                () -> "{\"id\": 123}");

        Outcome<String> ordersOutcome = userOutcome.flatMap(user ->
                boundary.call("OrderApi.fetchOrders",
                        () -> "[{\"orderId\": 1}, {\"orderId\": 2}]")
        );

        assertThat(ordersOutcome.isOk()).isTrue();
        assertThat(ordersOutcome.getOrThrow()).contains("orderId");
    }

    @Test
    void chainingOutcomes_failurePropagate() {
        // First call fails, second call should not execute
        var orderApiCalled = new boolean[]{false};

        Outcome<String> userOutcome = boundary.call("UserApi.fetchUser", () -> {
            throw new HttpConnectTimeoutException("User API down");
        });

        Outcome<String> ordersOutcome = userOutcome.flatMap(user -> {
            orderApiCalled[0] = true;
            return boundary.call("OrderApi.fetchOrders",
                    () -> "[{\"orderId\": 1}]");
        });

        assertThat(ordersOutcome.isFail()).isTrue();
        assertThat(orderApiCalled[0]).isFalse(); // Second call never happened
    }

    @Test
    void recoveringFromFailure() {
        Outcome<String> outcome = boundary.call("CacheApi.get", () -> {
            throw new HttpConnectTimeoutException("Cache unavailable");
        });

        // Recover by falling back to a default
        Outcome<String> recovered = outcome.recover(failure -> {
            System.out.println("Cache failed, using default: " + failure.message());
            return "default_value";
        });

        assertThat(recovered.isOk()).isTrue();
        assertThat(recovered.getOrThrow()).isEqualTo("default_value");
    }

    @Test
    void recoveringWithAlternativeCall() {
        Outcome<String> primaryOutcome = boundary.call("PrimaryDb.query", () -> {
            throw new HttpConnectTimeoutException("Primary DB unavailable");
        });

        // Recover by trying a secondary database
        Outcome<String> result = primaryOutcome.recoverWith(failure -> {
            System.out.println("Primary failed, trying secondary...");
            return boundary.call("SecondaryDb.query", () -> "data from secondary");
        });

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("data from secondary");
    }

    @Test
    void mappingSuccessfulOutcome() {
        Outcome<String> jsonOutcome = boundary.call("Api.fetch", () -> "{\"count\": 42}");

        // Transform the result
        Outcome<Integer> countOutcome = jsonOutcome.map(json -> {
            // Simple extraction (in real code, use a JSON library)
            return Integer.parseInt(json.replaceAll("\\D+", ""));
        });

        assertThat(countOutcome.isOk()).isTrue();
        assertThat(countOutcome.getOrThrow()).isEqualTo(42);
    }

    @Test
    void usingGetOrElse_forDefaults() {
        Outcome<String> outcome = boundary.call("Config.fetch", () -> {
            throw new HttpConnectTimeoutException("Config service down");
        });

        String config = outcome.getOrElse("{\"default\": true}");

        assertThat(config).isEqualTo("{\"default\": true}");
    }

    @Test
    void convenienceMethod_retrierWithBoundary() {
        RetryPolicy policy = RetryPolicy.fixed("simple", 2, Duration.ZERO);

        var attempts = new int[]{0};

        // Use the convenience method that combines Boundary + Retrier
        Outcome<String> outcome = retrier.execute(
                "Api.call",
                policy,
                boundary,
                () -> {
                    attempts[0]++;
                    if (attempts[0] < 2) {
                        throw new HttpConnectTimeoutException("Retry me");
                    }
                    return "success";
                }
        );

        assertThat(outcome.isOk()).isTrue();
        assertThat(attempts[0]).isEqualTo(2);
    }
}
