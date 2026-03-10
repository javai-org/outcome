package org.javai.outcome.examples;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.javai.outcome.Failure;
import org.javai.outcome.FailureType;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.boundary.HttpResponses;
import org.javai.outcome.ops.OpReporter;
import org.javai.outcome.retry.Retrier;
import org.javai.outcome.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test demonstrating that HTTP Retry-After headers flow through
 * the entire pipeline: HttpResponse → HttpStatusException → Failure.retryAfter → RetryPolicy.
 */
class RetryHttpRateLimitTest {

    record RetryEvent(Failure failure, int attemptNumber, Duration delay) {}

    @Test
    void retryRespectsHttpRetryAfterHeader() {
        List<Failure> reportedFailures = new ArrayList<>();
        List<RetryEvent> retryEvents = new ArrayList<>();

        OpReporter reporter = new OpReporter() {
            @Override
            public void report(Failure failure) {
                reportedFailures.add(failure);
            }

            @Override
            public void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay) {
                retryEvents.add(new RetryEvent(failure, attemptNumber, delay));
            }
        };

        Boundary boundary = Boundary.withReporter(reporter);
        Retrier retrier = Retrier.builder()
                .policy(RetryPolicy.backoff(3, Duration.ofMillis(100), Duration.ofSeconds(5)))
                .reporter(reporter)
                .build();

        AtomicInteger callCount = new AtomicInteger(0);

        // Simulate: first call returns 429 with Retry-After: 2, second call succeeds
        Outcome<String> result = retrier.execute(() ->
                boundary.call("Api.fetch", () -> {
                    int call = callCount.incrementAndGet();
                    if (call == 1) {
                        HttpResponse<String> response = stubResponse(429,
                                Map.of("Retry-After", List.of("2")));
                        HttpResponses.requireSuccess(response);
                    }
                    return "success";
                })
        );

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success");
        assertThat(callCount.get()).isEqualTo(2);

        // The failure should be classified as transient with retry hint
        // (reported twice: once by Boundary, once by Retrier)
        assertThat(reportedFailures).hasSizeGreaterThanOrEqualTo(1);
        Failure failure = reportedFailures.getFirst();
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(2));

        // The retry delay reported should be 2s (from Retry-After), not 100ms (from backoff initial)
        assertThat(retryEvents).hasSize(1);
        assertThat(retryEvents.getFirst().delay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void serverError503_isRetriedWithRetryAfter() {
        List<RetryEvent> retryEvents = new ArrayList<>();

        OpReporter reporter = new OpReporter() {
            @Override
            public void report(Failure failure) {}

            @Override
            public void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay) {
                retryEvents.add(new RetryEvent(failure, attemptNumber, delay));
            }
        };

        Boundary boundary = Boundary.silent();
        Retrier retrier = Retrier.builder()
                .policy(RetryPolicy.fixed(3, Duration.ofMillis(50)))
                .reporter(reporter)
                .build();

        AtomicInteger callCount = new AtomicInteger(0);

        Outcome<String> result = retrier.execute(() ->
                boundary.call("Api.fetch", () -> {
                    int call = callCount.incrementAndGet();
                    if (call == 1) {
                        HttpResponse<String> response = stubResponse(503,
                                Map.of("Retry-After", List.of("5")));
                        HttpResponses.requireSuccess(response);
                    }
                    return "recovered";
                })
        );

        assertThat(result.isOk()).isTrue();
        // Fixed policy delay is 50ms, but Retry-After: 5 (5000ms) should override
        assertThat(retryEvents).hasSize(1);
        assertThat(retryEvents.getFirst().delay()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void clientError404_isNotRetried() {
        Boundary boundary = Boundary.silent();
        Retrier retrier = Retrier.builder()
                .policy(RetryPolicy.fixed(3, Duration.ofMillis(10)))
                .build();

        AtomicInteger callCount = new AtomicInteger(0);

        Outcome<String> result = retrier.execute(() ->
                boundary.call("Api.fetch", () -> {
                    callCount.incrementAndGet();
                    HttpResponse<String> response = stubResponse(404, Map.of());
                    HttpResponses.requireSuccess(response);
                    return "never reached";
                })
        );

        assertThat(result.isFail()).isTrue();
        // 404 is PERMANENT — should not be retried
        assertThat(callCount.get()).isEqualTo(1);
    }

    private static HttpResponse<String> stubResponse(int statusCode, Map<String, List<String>> headers) {
        HttpHeaders httpHeaders = HttpHeaders.of(headers, (name, value) -> true);
        return new HttpResponse<>() {
            @Override public int statusCode() { return statusCode; }
            @Override public HttpHeaders headers() { return httpHeaders; }
            @Override public String body() { return ""; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("http://example.com"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
