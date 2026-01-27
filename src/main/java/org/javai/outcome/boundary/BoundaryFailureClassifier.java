package org.javai.outcome.boundary;

import org.javai.outcome.Failure;
import org.javai.outcome.FailureId;
import org.javai.outcome.FailureType;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Classifies checked exceptions into recoverable failures for use with {@link Boundary}.
 *
 * <p>This classifier handles common JDK checked exceptions (IO, network, SQL) and
 * translates them into appropriate {@link Failure} values with sensible defaults
 * for type and retry hints.
 *
 * <p>All failures produced by this classifier are either TRANSIENT or PERMANENT
 * since checked exceptions represent expected operational conditions that the
 * application can handle via retry, fallback, or graceful degradation.
 *
 * <p>This classifier should not be used for RuntimeExceptions. Use {@link
 * org.javai.outcome.ops.DefectClassifier} for uncaught exception handling.
 */
public class BoundaryFailureClassifier implements FailureClassifier {

    @Override
    public Failure classify(String operation, Throwable t) {
        // Network: transient, retryable
        if (t instanceof SocketTimeoutException) {
            return Failure.transientFailure(
                    FailureId.of("network", "timeout"),
                    messageFor("Socket timeout", t),
                    operation,
                    t,
                    Duration.ofMillis(500)
            );
        }

        if (t instanceof HttpTimeoutException) {
            return Failure.transientFailure(
                    FailureId.of("network", "http_timeout"),
                    messageFor("HTTP timeout", t),
                    operation,
                    t,
                    Duration.ofMillis(500)
            );
        }

        if (t instanceof ConnectException) {
            return Failure.transientFailure(
                    FailureId.of("network", "connection_refused"),
                    messageFor("Connection refused", t),
                    operation,
                    t,
                    Duration.ofSeconds(1)
            );
        }

        if (t instanceof UnknownHostException) {
            return Failure.permanentFailure(
                    FailureId.of("network", "unknown_host"),
                    messageFor("Unknown host", t),
                    operation,
                    t
            );
        }

        if (t instanceof TimeoutException) {
            return Failure.transientFailure(
                    FailureId.of("operation", "timeout"),
                    messageFor("Operation timeout", t),
                    operation,
                    t
            );
        }

        // File system: usually permanent
        if (t instanceof FileNotFoundException || t instanceof NoSuchFileException) {
            return Failure.permanentFailure(
                    FailureId.of("io", "file_not_found"),
                    messageFor("File not found", t),
                    operation,
                    t
            );
        }

        if (t instanceof AccessDeniedException) {
            return Failure.permanentFailure(
                    FailureId.of("io", "access_denied"),
                    messageFor("Access denied", t),
                    operation,
                    t
            );
        }

        // General IO: assume transient unless we know otherwise
        if (t instanceof IOException) {
            return Failure.transientFailure(
                    FailureId.of("io", "io_error"),
                    messageFor("IO error", t),
                    operation,
                    t
            );
        }

        // SQL: check for transient subtype
        if (t instanceof SQLTransientException) {
            return Failure.transientFailure(
                    FailureId.of("sql", "transient"),
                    messageFor("SQL transient error", t),
                    operation,
                    t
            );
        }

        if (t instanceof SQLException sqlEx) {
            return classifySqlException(operation, sqlEx);
        }

        // Fallback for unknown checked exceptions
        return classifyUnknownException(operation, t);
    }

    private static Failure classifySqlException(String operation, SQLException sqlEx) {
        String sqlState = sqlEx.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
            // Connection exceptions are transient
            return Failure.transientFailure(
                    FailureId.of("sql", "connection"),
                    messageFor("SQL connection error", sqlEx),
                    operation,
                    sqlEx
            );
        }
        // Unknown SQL errors - treat as permanent (conservative)
        return Failure.permanentFailure(
                FailureId.of("sql", "error"),
                messageFor("SQL error", sqlEx),
                operation,
                sqlEx
        );
    }

    private static Failure classifyUnknownException(String operation, Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        // Unknown checked exceptions - treat as permanent (conservative)
        return Failure.permanentFailure(
                FailureId.of("unknown", t.getClass().getSimpleName()),
                message,
                operation,
                t
        );
    }

    private static String messageFor(String prefix, Throwable t) {
        return prefix + ": " + t.getMessage();
    }
}
