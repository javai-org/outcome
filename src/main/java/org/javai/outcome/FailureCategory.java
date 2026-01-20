package org.javai.outcome;

/**
 * Classifies failures by their fundamental nature and recoverability.
 */
public enum FailureCategory {
    /**
     * Recoverable failure (network issues, service unavailable, rate limits).
     * These are expected in production and the application handles them via
     * retry, fallback, or graceful degradation.
     */
    RECOVERABLE,

    /**
     * Programming error or misconfiguration. No amount of retries will help.
     * Requires developer or operator intervention to fix the code or configuration.
     */
    DEFECT,

    /**
     * Terminal environment issue (out of memory, stack overflow).
     * The JVM is compromised. Let the process die and let infrastructure restart it.
     */
    TERMINAL
}
