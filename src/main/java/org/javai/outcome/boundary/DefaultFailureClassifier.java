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
 * Default classifier for common JDK exceptions.
 * Provides sensible defaults for IO, network, and SQL exceptions.
 */
public class DefaultFailureClassifier implements FailureClassifier {

    @Override
    public FailureKind classify(String operation, Throwable t) {
        Cause cause = Cause.fromThrowable(t);

        // Network: transient, retryable
        if (t instanceof SocketTimeoutException) {
            return FailureKind.transientOp(
                    FailureCode.of("network", "timeout"),
                    "Socket timeout: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.withDelay(Duration.ofMillis(500)));
        }

        if (t instanceof HttpTimeoutException) {
            return FailureKind.transientOp(
                    FailureCode.of("network", "http_timeout"),
                    "HTTP timeout: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.withDelay(Duration.ofMillis(500)));
        }

        if (t instanceof ConnectException) {
            return FailureKind.transientOp(
                    FailureCode.of("network", "connection_refused"),
                    "Connection refused: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.withDelay(Duration.ofSeconds(1)));
        }

        if (t instanceof UnknownHostException) {
            return FailureKind.permanentOp(
                    FailureCode.of("network", "unknown_host"),
                    "Unknown host: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof TimeoutException) {
            return FailureKind.transientOp(
                    FailureCode.of("operation", "timeout"),
                    "Operation timeout: " + t.getMessage(),
                    cause
            );
        }

        // File system: usually permanent
        if (t instanceof FileNotFoundException || t instanceof NoSuchFileException) {
            return FailureKind.permanentOp(
                    FailureCode.of("io", "file_not_found"),
                    "File not found: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof AccessDeniedException) {
            return FailureKind.permanentOp(
                    FailureCode.of("io", "access_denied"),
                    "Access denied: " + t.getMessage(),
                    cause
            );
        }

        // General IO: assume transient unless we know otherwise
        if (t instanceof IOException) {
            return FailureKind.transientOp(
                    FailureCode.of("io", "io_error"),
                    "IO error: " + t.getMessage(),
                    cause
            ).withRetryHint(RetryHint.maybe("io_unknown"));
        }

        // SQL: check for transient subtype
        if (t instanceof SQLTransientException) {
            return FailureKind.transientOp(
                    FailureCode.of("sql", "transient"),
                    "SQL transient error: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof SQLException sqlEx) {
            // Classify by SQL state if available
            String sqlState = sqlEx.getSQLState();
            if (sqlState != null && sqlState.startsWith("08")) {
                // Connection exceptions
                return FailureKind.transientOp(
                        FailureCode.of("sql", "connection"),
                        "SQL connection error: " + t.getMessage(),
                        cause
                );
            }
            return new FailureKind(
                    FailureCode.of("sql", "error"),
                    "SQL error: " + t.getMessage(),
                    FailureCategory.OPERATIONAL,
                    FailureStability.UNKNOWN,
                    RetryHint.maybe("sql_unknown"),
                    cause
            );
        }

        // Programming errors: not retryable
        if (t instanceof IllegalArgumentException || t instanceof IllegalStateException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "invalid_argument"),
                    "Invalid argument: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof NullPointerException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "null_pointer"),
                    "Null pointer: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof UnsupportedOperationException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "unsupported_operation"),
                    "Unsupported operation: " + t.getMessage(),
                    cause
            );
        }

        // Fallback: unknown, assume operational with unknown stability
        return new FailureKind(
                FailureCode.of("unknown", t.getClass().getSimpleName()),
                t.getMessage() != null ? t.getMessage() : t.getClass().getName(),
                FailureCategory.OPERATIONAL,
                FailureStability.UNKNOWN,
                RetryHint.maybe("unknown_exception"),
                cause
        );
    }
}
