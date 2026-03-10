package org.javai.outcome.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpResponsesTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 299})
    void requireSuccess_doesNotThrowFor2xx(int statusCode) {
        HttpResponse<String> response = stubResponse(statusCode, Map.of());

        assertThatCode(() -> HttpResponses.requireSuccess(response))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 502, 503})
    void requireSuccess_throwsForNon2xx(int statusCode) {
        HttpResponse<String> response = stubResponse(statusCode, Map.of());

        assertThatThrownBy(() -> HttpResponses.requireSuccess(response))
                .isInstanceOf(HttpStatusException.class)
                .satisfies(ex -> {
                    HttpStatusException hse = (HttpStatusException) ex;
                    assertThat(hse.statusCode()).isEqualTo(statusCode);
                    assertThat(hse.getMessage()).isEqualTo("HTTP " + statusCode);
                });
    }

    @Test
    void requireSuccess_parsesRetryAfterSeconds() {
        HttpResponse<String> response = stubResponse(429,
                Map.of("Retry-After", List.of("30")));

        assertThatThrownBy(() -> HttpResponses.requireSuccess(response))
                .isInstanceOf(HttpStatusException.class)
                .satisfies(ex -> {
                    HttpStatusException hse = (HttpStatusException) ex;
                    assertThat(hse.statusCode()).isEqualTo(429);
                    assertThat(hse.retryAfter()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void requireSuccess_parsesRetryAfterOnServerError() {
        HttpResponse<String> response = stubResponse(503,
                Map.of("Retry-After", List.of("60")));

        assertThatThrownBy(() -> HttpResponses.requireSuccess(response))
                .isInstanceOf(HttpStatusException.class)
                .satisfies(ex -> {
                    HttpStatusException hse = (HttpStatusException) ex;
                    assertThat(hse.statusCode()).isEqualTo(503);
                    assertThat(hse.retryAfter()).isEqualTo(Duration.ofSeconds(60));
                });
    }

    @Test
    void requireSuccess_noRetryAfterHeader_retryAfterIsNull() {
        HttpResponse<String> response = stubResponse(429, Map.of());

        assertThatThrownBy(() -> HttpResponses.requireSuccess(response))
                .isInstanceOf(HttpStatusException.class)
                .satisfies(ex -> {
                    HttpStatusException hse = (HttpStatusException) ex;
                    assertThat(hse.retryAfter()).isNull();
                });
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
