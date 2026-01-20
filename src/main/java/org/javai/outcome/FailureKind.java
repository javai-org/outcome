package org.javai.outcome;

import java.util.Objects;

/**
 * Describes a failure without operational context.
 * This is what classifiers produce; the Boundary adds context to create a full Failure.
 *
 * @param code Namespaced failure identifier
 * @param message Human-readable description
 * @param category The nature of the failure
 * @param stability Whether the failure is transient or permanent
 * @param retryHint Advisory retry guidance
 * @param cause Underlying cause for diagnostics (may be null)
 */
public record FailureKind(
        FailureCode code,
        String message,
        FailureCategory category,
        FailureStability stability,
        RetryHint retryHint,
        Cause cause
) {

    public FailureKind {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(stability, "stability must not be null");
        Objects.requireNonNull(retryHint, "retryHint must not be null");
    }

    /**
     * Creates a transient operational failure that should be retried.
     */
    public static FailureKind transientOp(FailureCode code, String message, Cause cause) {
        return new FailureKind(
                code,
                message,
                FailureCategory.OPERATIONAL,
                FailureStability.TRANSIENT,
                RetryHint.yes(),
                cause
        );
    }

    /**
     * Creates a permanent operational failure that should not be retried.
     */
    public static FailureKind permanentOp(FailureCode code, String message, Cause cause) {
        return new FailureKind(
                code,
                message,
                FailureCategory.OPERATIONAL,
                FailureStability.PERMANENT,
                RetryHint.none(),
                cause
        );
    }

    /**
     * Creates a defect/misconfiguration failure.
     */
    public static FailureKind defect(FailureCode code, String message, Cause cause) {
        return new FailureKind(
                code,
                message,
                FailureCategory.DEFECT_OR_MISCONFIGURATION,
                FailureStability.PERMANENT,
                RetryHint.none(),
                cause
        );
    }

    /**
     * Creates a failure with custom retry hint.
     */
    public FailureKind withRetryHint(RetryHint hint) {
        return new FailureKind(code, message, category, stability, hint, cause);
    }
}
