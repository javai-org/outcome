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
    void socketTimeout_isTransientRecoverable() {
        FailureKind kind = classifier.classify("HttpCall", new SocketTimeoutException("timed out"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("network", "timeout"));
        assertThat(kind.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(kind.stability()).isEqualTo(FailureStability.TRANSIENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.YES);
        assertThat(kind.retryHint().minDelay()).isNotNull();
    }

    @Test
    void connectException_isTransientRecoverable() {
        FailureKind kind = classifier.classify("Connect", new ConnectException("refused"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("network", "connection_refused"));
        assertThat(kind.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(kind.stability()).isEqualTo(FailureStability.TRANSIENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.YES);
    }

    @Test
    void unknownHost_isPermanentRecoverable() {
        FailureKind kind = classifier.classify("Resolve", new UnknownHostException("bad.host"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("network", "unknown_host"));
        assertThat(kind.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.NONE);
    }

    @Test
    void fileNotFound_isPermanentRecoverable() {
        FailureKind kind = classifier.classify("ReadFile", new FileNotFoundException("/missing/file"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("io", "file_not_found"));
        assertThat(kind.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.NONE);
    }

    @Test
    void genericIOException_isTransientWithMaybeRetry() {
        FailureKind kind = classifier.classify("IO", new IOException("something"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("io", "io_error"));
        assertThat(kind.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(kind.stability()).isEqualTo(FailureStability.TRANSIENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.MAYBE);
    }

    @Test
    void sqlTransientException_isTransient() {
        FailureKind kind = classifier.classify("Query",
                new SQLTransientConnectionException("connection lost"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("sql", "transient"));
        assertThat(kind.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(kind.stability()).isEqualTo(FailureStability.TRANSIENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.YES);
    }

    @Test
    void sqlConnectionException_isTransient() {
        SQLException sqlEx = new SQLException("connection error", "08001");
        FailureKind kind = classifier.classify("Query", sqlEx);

        assertThat(kind.code()).isEqualTo(FailureCode.of("sql", "connection"));
        assertThat(kind.stability()).isEqualTo(FailureStability.TRANSIENT);
    }

    @Test
    void genericSQLException_hasUnknownStability() {
        SQLException sqlEx = new SQLException("some error", "42000");
        FailureKind kind = classifier.classify("Query", sqlEx);

        assertThat(kind.code()).isEqualTo(FailureCode.of("sql", "error"));
        assertThat(kind.stability()).isEqualTo(FailureStability.UNKNOWN);
    }

    @Test
    void unknownCheckedException_isRecoverable() {
        Exception customChecked = new Exception("unknown checked");
        FailureKind kind = classifier.classify("Unknown", customChecked);

        assertThat(kind.code().namespace()).isEqualTo("unknown");
        assertThat(kind.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(kind.stability()).isEqualTo(FailureStability.UNKNOWN);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.MAYBE);
    }

    @Test
    void cause_isPopulated() {
        Exception ex = new SocketTimeoutException("timeout");
        FailureKind kind = classifier.classify("Op", ex);

        assertThat(kind.cause()).isNotNull();
        assertThat(kind.cause().type()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(kind.cause().detail()).isEqualTo("timeout");
    }
}
