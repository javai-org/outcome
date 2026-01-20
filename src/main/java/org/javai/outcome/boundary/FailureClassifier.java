package org.javai.outcome.boundary;

import org.javai.outcome.FailureKind;

/**
 * Classifies exceptions into structured failures.
 * Implementations should provide deterministic, context-aware classification.
 */
@FunctionalInterface
public interface FailureClassifier {

    /**
     * Classifies an exception into a FailureKind.
     *
     * @param operation The operation that was being performed
     * @param throwable The exception that occurred
     * @return A classified FailureKind
     */
    FailureKind classify(String operation, Throwable throwable);
}
