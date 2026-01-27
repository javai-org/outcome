package org.javai.outcome.boundary;

import org.javai.outcome.Failure;

/**
 * Classifies exceptions into structured failures.
 * Implementations should provide deterministic, context-aware classification.
 */
@FunctionalInterface
public interface FailureClassifier {

    /**
     * Classifies an exception into a Failure.
     *
     * @param operation The operation that was being performed
     * @param throwable The exception that occurred
     * @return A classified Failure
     */
    Failure classify(String operation, Throwable throwable);
}
