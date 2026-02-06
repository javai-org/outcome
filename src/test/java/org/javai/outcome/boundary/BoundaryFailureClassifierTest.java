package org.javai.outcome.boundary;

import org.javai.outcome.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.*;

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

    @Test
    void exception_isPopulated() {
        Exception ex = new SocketTimeoutException("timeout");
        Failure failure = classifier.classify("Op", ex);

        assertThat(failure.exception()).isNotNull();
        assertThat(failure.exception()).isEqualTo(ex);
        assertThat(failure.exception().getMessage()).isEqualTo("timeout");
    }
}
