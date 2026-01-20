package org.javai.outcome;

/**
 * Indicates whether retrying an operation might succeed.
 */
public enum Retryability {
    /**
     * Do not retry. The failure is permanent or retrying would cause harm.
     */
    NONE,

    /**
     * Retry might help but is not guaranteed.
     */
    MAYBE,

    /**
     * Retry is recommended and likely to succeed.
     */
    YES
}
