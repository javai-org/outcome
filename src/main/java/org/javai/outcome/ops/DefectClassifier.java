package org.javai.outcome.ops;

import org.javai.outcome.*;
import org.javai.outcome.boundary.FailureClassifier;

/**
 * Classifies uncaught exceptions (defects) for use with {@link OperationalExceptionHandler}.
 *
 * <p>This classifier handles RuntimeExceptions that propagated through the application
 * without being caught. These represent defects — bugs or misconfigurations that
 * require human intervention to fix.
 *
 * <p>All failures produced by this classifier are {@link FailureCategory#DEFECT}
 * with {@link FailureStability#PERMANENT} and no retry hint, since retrying a
 * defect will not help.
 *
 * <p>This classifier should not be used for checked exceptions. Use {@link
 * org.javai.outcome.boundary.BoundaryFailureClassifier} for Boundary exception handling.
 */
public class DefectClassifier implements FailureClassifier {

    @Override
    public FailureKind classify(String operation, Throwable t) {
        Cause cause = Cause.fromThrowable(t);

        if (t instanceof NullPointerException) {
            return defect("null_pointer", "Null pointer", t, cause);
        }

        if (t instanceof IllegalArgumentException) {
            return defect("illegal_argument", "Illegal argument", t, cause);
        }

        if (t instanceof IllegalStateException) {
            return defect("illegal_state", "Illegal state", t, cause);
        }

        if (t instanceof UnsupportedOperationException) {
            return defect("unsupported_operation", "Unsupported operation", t, cause);
        }

        if (t instanceof IndexOutOfBoundsException) {
            return defect("index_out_of_bounds", "Index out of bounds", t, cause);
        }

        if (t instanceof ClassCastException) {
            return defect("class_cast", "Class cast error", t, cause);
        }

        if (t instanceof ArithmeticException) {
            return defect("arithmetic", "Arithmetic error", t, cause);
        }

        // Fallback for any other uncaught exception
        return classifyUnknownDefect(t, cause);
    }

    private static FailureKind defect(String code, String prefix, Throwable t, Cause cause) {
        return FailureKind.defect(
                FailureCode.of("defect", code),
                messageFor(prefix, t),
                cause
        );
    }

    private static FailureKind classifyUnknownDefect(Throwable t, Cause cause) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        return FailureKind.defect(
                FailureCode.of("defect", "uncategorized"),
                message,
                cause
        );
    }

    private static String messageFor(String prefix, Throwable t) {
        return prefix + ": " + t.getMessage();
    }
}
