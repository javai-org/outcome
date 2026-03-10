package org.javai.outcome.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import org.javai.outcome.Failure;
import org.javai.outcome.FailureId;
import org.javai.outcome.FailureType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoundaryFailureClassifierTest {

    private BoundaryFailureClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new BoundaryFailureClassifier();
    }

    @Test
    void socketTimeout_isTransient() {
        Failure failure = classifier.classify("HttpCall", new SocketTimeoutException("timed out"));

        assertThat(failure.id()).isEqualTo(FailureId.of("network", "timeout"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void connectException_isTransient() {
        Failure failure = classifier.classify("Connect", new ConnectException("refused"));

        assertThat(failure.id()).isEqualTo(FailureId.of("network", "connection_refused"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unknownHost_isPermanent() {
        Failure failure = classifier.classify("Resolve", new UnknownHostException("bad.host"));

        assertThat(failure.id()).isEqualTo(FailureId.of("network", "unknown_host"));
        assertThat(failure.type()).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void fileNotFound_isPermanent() {
        Failure failure = classifier.classify("ReadFile", new FileNotFoundException("/missing/file"));

        assertThat(failure.id()).isEqualTo(FailureId.of("io", "file_not_found"));
        assertThat(failure.type()).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void genericIOException_isTransient() {
        Failure failure = classifier.classify("IO", new IOException("something"));

        assertThat(failure.id()).isEqualTo(FailureId.of("io", "io_error"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void sqlTransientException_isTransient() {
        Failure failure = classifier.classify("Query",
                new SQLTransientConnectionException("connection lost"));

        assertThat(failure.id()).isEqualTo(FailureId.of("sql", "transient"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void sqlConnectionException_isTransient() {
        SQLException sqlEx = new SQLException("connection error", "08001");
        Failure failure = classifier.classify("Query", sqlEx);

        assertThat(failure.id()).isEqualTo(FailureId.of("sql", "connection"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void genericSQLException_isPermanent() {
        SQLException sqlEx = new SQLException("some error", "42000");
        Failure failure = classifier.classify("Query", sqlEx);

        assertThat(failure.id()).isEqualTo(FailureId.of("sql", "error"));
        assertThat(failure.type()).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void unknownCheckedException_isPermanent() {
        Exception customChecked = new Exception("unknown checked");
        Failure failure = classifier.classify("Unknown", customChecked);

        assertThat(failure.id().namespace()).isEqualTo("unknown");
        assertThat(failure.type()).isEqualTo(FailureType.PERMANENT);
    }

    // === HTTP status classification ===

    @Test
    void httpStatus429_isTransientWithRateLimitedId() {
        HttpStatusException ex = new HttpStatusException(429, "HTTP 429", Duration.ofSeconds(30));
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "rate_limited"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(30));
        assertThat(failure.exception()).contains(ex);
    }

    @Test
    void httpStatus429_withoutRetryAfter_isTransientWithNullHint() {
        HttpStatusException ex = new HttpStatusException(429, "HTTP 429");
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "rate_limited"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
        assertThat(failure.retryAfter()).isEmpty();
    }

    @Test
    void httpStatus500_isTransientServerError() {
        HttpStatusException ex = new HttpStatusException(500, "HTTP 500");
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "server_error"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void httpStatus503_isTransientWithRetryAfter() {
        HttpStatusException ex = new HttpStatusException(503, "HTTP 503", Duration.ofSeconds(60));
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "server_error"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(60));
    }

    @Test
    void httpStatus502_isTransientServerError() {
        HttpStatusException ex = new HttpStatusException(502, "HTTP 502");
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "server_error"));
        assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void httpStatus400_isPermanentClientError() {
        HttpStatusException ex = new HttpStatusException(400, "HTTP 400");
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "client_error"));
        assertThat(failure.type()).isEqualTo(FailureType.PERMANENT);
        assertThat(failure.retryAfter()).isEmpty();
    }

    @Test
    void httpStatus404_isPermanentClientError() {
        HttpStatusException ex = new HttpStatusException(404, "HTTP 404");
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "client_error"));
        assertThat(failure.type()).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void httpStatus401_isPermanentClientError() {
        HttpStatusException ex = new HttpStatusException(401, "HTTP 401");
        Failure failure = classifier.classify("Api.fetch", ex);

        assertThat(failure.id()).isEqualTo(FailureId.of("http", "client_error"));
        assertThat(failure.type()).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void httpStatus_operationIsPreserved() {
        HttpStatusException ex = new HttpStatusException(503, "HTTP 503");
        Failure failure = classifier.classify("OrderService.placeOrder", ex);

        assertThat(failure.operation()).isEqualTo("OrderService.placeOrder");
    }

    @Test
    void exception_isPopulated() {
        Exception ex = new SocketTimeoutException("timeout");
        Failure failure = classifier.classify("Op", ex);

        assertThat(failure.exception()).isPresent();
        assertThat(failure.exception()).contains(ex);
        assertThat(failure.exception().get().getMessage()).isEqualTo("timeout");
    }
}
