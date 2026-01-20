package org.javai.outcome.boundary;

import org.javai.outcome.*;

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
 * translates them into appropriate {@link FailureKind} values with sensible defaults
 * for stability and retry hints.
 *
 * <p>All failures produced by this classifier are {@link FailureCategory#RECOVERABLE}
 * since checked exceptions represent expected operational conditions that the
 * application can handle via retry, fallback, or graceful degradation.
 *
 * <p>This classifier should not be used for RuntimeExceptions. Use {@link
 * org.javai.outcome.ops.DefectClassifier} for uncaught exception handling.
 */
public class BoundaryFailureClassifier implements FailureClassifier {

    @Override
    public FailureKind classify(String operation, Throwable t) {
        Cause cause = Cause.fromThrowable(t);

        // Network: transient, retryable
        if (t instanceof SocketTimeoutException) {
            return FailureKind.transientFailure(
                    FailureCode.of("network", "timeout"),
                    "Socket timeout: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.withDelay(Duration.ofMillis(500)));
        }

        if (t instanceof HttpTimeoutException) {
            return FailureKind.transientFailure(
                    FailureCode.of("network", "http_timeout"),
                    "HTTP timeout: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.withDelay(Duration.ofMillis(500)));
        }

        if (t instanceof ConnectException) {
            return FailureKind.transientFailure(
                    FailureCode.of("network", "connection_refused"),
                    "Connection refused: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.withDelay(Duration.ofSeconds(1)));
        }

        if (t instanceof UnknownHostException) {
            return FailureKind.permanentFailure(
                    FailureCode.of("network", "unknown_host"),
                    "Unknown host: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof TimeoutException) {
            return FailureKind.transientFailure(
                    FailureCode.of("operation", "timeout"),
                    "Operation timeout: " + t.getMessage(),
                    cause
            );
        }

        // File system: usually permanent
        if (t instanceof FileNotFoundException || t instanceof NoSuchFileException) {
            return FailureKind.permanentFailure(
                    FailureCode.of("io", "file_not_found"),
                    "File not found: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof AccessDeniedException) {
            return FailureKind.permanentFailure(
                    FailureCode.of("io", "access_denied"),
                    "Access denied: " + t.getMessage(),
                    cause
            );
        }

        // General IO: assume transient unless we know otherwise
        if (t instanceof IOException) {
            return FailureKind.transientFailure(
                    FailureCode.of("io", "io_error"),
                    "IO error: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.maybe("io_unknown"));
        }

        // SQL: check for transient subtype
        if (t instanceof SQLTransientException) {
            return FailureKind.transientFailure(
                    FailureCode.of("sql", "transient"),
                    "SQL transient error: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof SQLException sqlEx) {
            String sqlState = sqlEx.getSQLState();
            if (sqlState != null && sqlState.startsWith("08")) {
                // Connection exceptions
                return FailureKind.transientFailure(
                        FailureCode.of("sql", "connection"),
                        "SQL connection error: " + t.getMessage(),
                        cause
                );
            }
            return new FailureKind(
                    FailureCode.of("sql", "error"),
                    "SQL error: " + t.getMessage(),
                    FailureCategory.RECOVERABLE,
                    FailureStability.UNKNOWN,
                    RetryHint.maybe("sql_unknown"),
                    cause
            );
        }

        // Fallback for unknown checked exceptions
        return new FailureKind(
                FailureCode.of("unknown", t.getClass().getSimpleName()),
                t.getMessage() != null ? t.getMessage() : t.getClass().getName(),
                FailureCategory.RECOVERABLE,
                FailureStability.UNKNOWN,
                RetryHint.maybe("unknown_exception"),
                cause
        );
    }
}
