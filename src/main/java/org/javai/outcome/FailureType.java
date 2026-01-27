package org.javai.outcome;

/**
 * Classifies failures by their nature and expected behavior.
 */
public enum FailureType {
    /**
     * Temporary failure that may resolve on retry.
     * Examples: network timeout, service temporarily unavailable, rate limited.
     */
    TRANSIENT,

    /**
     * Permanent failure that will not resolve on retry.
     * Examples: invalid credentials, resource not found, access denied.
     */
    PERMANENT,

    /**
     * Programming error or misconfiguration.
     * Requires developer or operator intervention to fix.
     * Examples: null pointer, illegal argument, missing configuration.
     */
    DEFECT
}
