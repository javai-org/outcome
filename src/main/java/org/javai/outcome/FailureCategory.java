package org.javai.outcome;

/**
 * Classifies failures by their fundamental nature.
 */
public enum FailureCategory {
    /**
     * Expected operational failure (network issues, service unavailable, rate limits).
     * These are normal in production and should be handled gracefully.
     */
    OPERATIONAL,

    /**
     * Programming error or misconfiguration. No amount of retries will help.
     * Requires developer or operator intervention.
     */
    DEFECT_OR_MISCONFIGURATION,

    /**
     * Fatal environment issue (disk full, out of memory, certificate expired).
     * The application cannot function until the environment is fixed.
     */
    FATAL_ENVIRONMENT
}
