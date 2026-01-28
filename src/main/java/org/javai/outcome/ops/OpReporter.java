package org.javai.outcome.ops;

import org.javai.outcome.Failure;

/**
 * Reports failures for observability and operator notification.
 * Implementations might emit metrics, structured logs, or alerts.
 */
public interface OpReporter {

    /**
     * Reports a failure occurrence.
     */
    void report(Failure failure);

    /**
     * Reports a retry attempt.
     *
     * @param failure The failure that triggered the retry
     * @param attemptNumber The current attempt number (1-based)
     */
    default void reportRetryAttempt(Failure failure, int attemptNumber) {
        // Default: no-op. Implementations may override.
    }

    /**
     * Reports that retry attempts have been exhausted.
     *
     * @param failure The final failure
     * @param totalAttempts The total number of attempts made
     */
    default void reportRetryExhausted(Failure failure, int totalAttempts) {
        // Default: no-op. Implementations may override.
    }

    /**
     * A reporter that does nothing. Useful for testing.
     */
    static OpReporter noOp() {
        return failure -> {};
    }

    /**
     * Creates a composite reporter that fans out to all given reporters.
     *
     * @param reporters the reporters to delegate to
     * @return a composite reporter
     */
    static OpReporter composite(OpReporter... reporters) {
        return CompositeOpReporter.of(reporters);
    }
}
