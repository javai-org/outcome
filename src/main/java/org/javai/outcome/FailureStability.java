package org.javai.outcome;

/**
 * Indicates whether a failure is expected to persist or resolve on its own.
 */
public enum FailureStability {
    /**
     * The failure is likely temporary (network blip, service restarting).
     * Retry may succeed.
     */
    TRANSIENT,

    /**
     * The failure is permanent (invalid credentials, resource deleted).
     * Retry will not help.
     */
    PERMANENT,

    /**
     * Stability cannot be determined from available information.
     */
    UNKNOWN
}
