package org.javai.outcome;

import java.util.Objects;

/**
 * Captures the underlying cause of a failure for diagnostics.
 *
 * @param type The exception class name or error type
 * @param fingerprint A stable identifier for deduplication (e.g., hash of stack trace)
 * @param detail Human-readable details (message, relevant stack frames)
 */
public record Cause(String type, String fingerprint, String detail) {

    public Cause {
        Objects.requireNonNull(type, "type must not be null");
    }

    public static Cause fromThrowable(Throwable t) {
        Objects.requireNonNull(t, "throwable must not be null");
        String type = t.getClass().getName();
        String fingerprint = computeFingerprint(t);
        String detail = t.getMessage();
        return new Cause(type, fingerprint, detail);
    }

    private static String computeFingerprint(Throwable t) {
        StackTraceElement[] stack = t.getStackTrace();
        if (stack.length == 0) {
            return t.getClass().getName();
        }
        StackTraceElement top = stack[0];
        return t.getClass().getSimpleName() + "@" + top.getClassName() + ":" + top.getLineNumber();
    }
}
